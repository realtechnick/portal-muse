// Command wakeparity runs zserge/microwakeword, the Go detector the Kotlin port in android/wake was
// made from, over WAV files and records what it computed at every 10 ms step: the front end's raw
// and int8 features, the model's output whenever an inference ran, and the sliding-window average.
// It also records where Detector.ProcessAudio reports a detection when fed 160-sample chunks.
// ParityTest in android/wake replays the same cases and asserts the Kotlin port computes the same.
// mkwav.ps1 made the WAVs under parity/; the others are the library's testdata.
//
//	go run . -res ../../android/wake/src/test/resources
package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"math"
	"os"
	"path/filepath"

	mww "github.com/zserge/microwakeword"
	"github.com/zserge/microwakeword/audiofrontend"
)

type parityCase struct {
	name, model, wav string
	window, stepMs    int
	cutoff            float64
}

var cases = []parityCase{
	{"hey_muse-hey_muse_david", "hey_muse.tflite", "parity/hey_muse_david.wav", 6, 10, 0.99},
	{"hey_muse-hey_muse_zira", "hey_muse.tflite", "parity/hey_muse_zira.wav", 6, 10, 0.99},
	{"hey_muse-speech_zira", "hey_muse.tflite", "parity/speech_zira.wav", 6, 10, 0.99},
	{"hey_muse-noise", "hey_muse.tflite", "parity/noise.wav", 6, 10, 0.99},
	{"hey_muse-silence", "hey_muse.tflite", "parity/silence.wav", 6, 10, 0.99},
	{"hey_muse-alexa", "hey_muse.tflite", "testdata/alexa.wav", 6, 10, 0.99},
	{"hey_muse-hey_jarvis", "hey_muse.tflite", "testdata/hey_jarvis.wav", 6, 10, 0.99},
	{"hey_muse-nowake", "hey_muse.tflite", "testdata/nowake.wav", 6, 10, 0.99},
	{"hey_muse-okay_nabu", "hey_muse.tflite", "testdata/okay_nabu.wav", 6, 10, 0.99},
	{"hey_jarvis-hey_jarvis", "testdata/hey_jarvis.tflite", "testdata/hey_jarvis.wav", 5, 10, 0.85},
	{"alexa-alexa", "testdata/alexa.tflite", "testdata/alexa.wav", 5, 10, 0.85},
}

// neverFires keeps the detector's own threshold from triggering, so ProcessFeatures never returns
// early and every step is scored.
const neverFires = 1.1

const chunk = 160

func main() {
	res := flag.String("res", "../../android/wake/src/test/resources", "test resources directory of the :wake module")
	flag.Parse()

	index, err := os.Create(filepath.Join(*res, "parity", "cases.txt"))
	check(err)
	defer index.Close()
	for _, c := range cases {
		check(run(*res, c))
		fmt.Fprintf(index, "%s %s %s %d %d %g\n", c.name, c.model, c.wav, c.window, c.stepMs, c.cutoff)
	}
}

func run(res string, c parityCase) error {
	samples, err := readWAV(filepath.Join(res, c.wav))
	if err != nil {
		return fmt.Errorf("%s: %w", c.wav, err)
	}
	modelPath := filepath.Join(res, c.model)

	det, err := mww.NewDetector(mww.Config{
		ModelPath: modelPath, ProbabilityCutoff: neverFires, SlidingWindowSize: c.window, FeaturesStepMs: c.stepMs,
	})
	if err != nil {
		return err
	}
	var inferred bool
	var rawProb uint8
	det.OnInference = func(_ int, p uint8, _ float64) { inferred, rawProb = true, p }

	feCfg := audiofrontend.DefaultConfig(c.stepMs)
	fe := audiofrontend.New(feCfg)
	window := feCfg.SampleRate * feCfg.WindowSizeMs / 1000
	step := feCfg.SampleRate * feCfg.StepSizeMs / 1000

	f, err := os.Create(filepath.Join(res, "parity", c.name+".bin"))
	if err != nil {
		return err
	}
	defer f.Close()
	w := bufio.NewWriter(f)
	defer w.Flush()

	steps := 0
	for off := 0; off+window <= len(samples); off += step {
		steps++
	}
	put(w, []byte("MWWP"), uint32(1), uint32(steps))

	var maxProb uint8
	maxAvg := 0.0
	for off := 0; off+window <= len(samples); off += step {
		raw := fe.ProcessFrame(samples[off : off+window])
		q := quantize(raw)
		inferred = false
		det.ProcessFeatures([][]int8{q})
		avg := det.SlidingAverage()
		put(w, raw, q, inferred, rawProb, math.Float64bits(avg))
		if inferred {
			maxProb = max(maxProb, rawProb)
		}
		maxAvg = max(maxAvg, avg)
	}

	streaming, err := mww.NewDetector(mww.Config{
		ModelPath: modelPath, ProbabilityCutoff: c.cutoff, SlidingWindowSize: c.window, FeaturesStepMs: c.stepMs,
	})
	if err != nil {
		return err
	}
	var hits []uint32
	for i := 0; i+chunk <= len(samples); i += chunk {
		if streaming.ProcessAudio(samples[i : i+chunk]) {
			hits = append(hits, uint32(i))
		}
	}
	put(w, uint32(len(hits)), hits)

	fmt.Printf("%-24s %5d steps  max prob %3d/255 (%.4f)  max avg %.4f  detections at %v\n",
		c.name, steps, maxProb, float64(maxProb)/255, maxAvg, seconds(hits))
	return nil
}

// quantize is the detector's own int8 conversion (ProcessAudio in microwakeword.go).
func quantize(raw []uint16) []int8 {
	frame := make([]int8, len(raw))
	for i, v := range raw {
		val := (int32(v)*256+333)/666 - 128
		frame[i] = int8(max(-128, min(127, val)))
	}
	return frame
}

func put(w *bufio.Writer, values ...any) {
	for _, v := range values {
		check(binary.Write(w, binary.LittleEndian, v))
	}
}

// readWAV returns the samples of the data chunk of a 16 kHz mono PCM16 WAV.
func readWAV(path string) ([]int16, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(data) < 12 || string(data[0:4]) != "RIFF" || string(data[8:12]) != "WAVE" {
		return nil, errors.New("not a RIFF/WAVE file")
	}
	for off := 12; off+8 <= len(data); {
		id := string(data[off : off+4])
		size := int(binary.LittleEndian.Uint32(data[off+4:]))
		body := data[off+8 : min(len(data), off+8+size)]
		switch id {
		case "fmt ":
			format := binary.LittleEndian.Uint16(body[0:])
			channels := binary.LittleEndian.Uint16(body[2:])
			rate := binary.LittleEndian.Uint32(body[4:])
			bits := binary.LittleEndian.Uint16(body[14:])
			if format != 1 || channels != 1 || rate != 16000 || bits != 16 {
				return nil, fmt.Errorf("want 16 kHz mono PCM16, got format %d, %d channels, %d Hz, %d bits", format, channels, rate, bits)
			}
		case "data":
			samples := make([]int16, len(body)/2)
			for i := range samples {
				samples[i] = int16(binary.LittleEndian.Uint16(body[i*2:]))
			}
			return samples, nil
		}
		off += 8 + size + size%2
	}
	return nil, errors.New("no data chunk")
}

func seconds(hits []uint32) []string {
	out := make([]string, len(hits))
	for i, h := range hits {
		out[i] = fmt.Sprintf("%.2fs", float64(h)/16000)
	}
	return out
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "wakeparity:", err)
		os.Exit(1)
	}
}
