package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/wobsoriano/muse-portal/core/internal/muse"
)

// app is the Android side of the pipe, driving a real musecore loop.
type app struct {
	t     *testing.T
	in    *io.PipeWriter
	lines chan string
	done  chan struct{}
	once  sync.Once
}

func start(t *testing.T, dir string) *app {
	t.Helper()
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	a := &app{t: t, in: inW, lines: make(chan string, 256), done: make(chan struct{})}
	go func() {
		run(context.Background(), dir, inR, outW)
		outW.Close()
		close(a.done)
	}()
	go func() {
		s := bufio.NewScanner(outR)
		for s.Scan() {
			a.lines <- s.Text()
		}
		close(a.lines)
	}()
	t.Cleanup(func() { a.close() })
	return a
}

func (a *app) send(line string) {
	a.t.Helper()
	if _, err := io.WriteString(a.in, line+"\n"); err != nil {
		a.t.Fatalf("send %s: %v", line, err)
	}
}

func (a *app) next() string {
	a.t.Helper()
	select {
	case l, ok := <-a.lines:
		if !ok {
			a.t.Fatal("musecore closed stdout")
		}
		return l
	case <-time.After(5 * time.Second):
		a.t.Fatal("no line from musecore")
	}
	return ""
}

func (a *app) expect(want ...string) {
	a.t.Helper()
	for _, w := range want {
		if got := a.next(); got != w {
			a.t.Fatalf("got  %s\nwant %s", got, w)
		}
	}
}

// expectAnyOrder takes the next len(want) lines, which may come in any order.
func (a *app) expectAnyOrder(want ...string) {
	a.t.Helper()
	var got []string
	for range want {
		got = append(got, a.next())
	}
	slices.Sort(got)
	want = slices.Clone(want)
	slices.Sort(want)
	if !slices.Equal(got, want) {
		a.t.Fatalf("got  %q\nwant %q", got, want)
	}
}

// close ends stdin and returns what musecore said on its way out.
func (a *app) close() []string {
	var rest []string
	a.once.Do(func() {
		a.in.Close()
		for l := range a.lines {
			rest = append(rest, l)
		}
		select {
		case <-a.done:
		case <-time.After(5 * time.Second):
			a.t.Error("musecore did not stop")
		}
	})
	return rest
}

func readState(t *testing.T, dir string) muse.State {
	t.Helper()
	st, err := muse.FileStore{Path: filepath.Join(dir, "muse.json")}.Load()
	if err != nil {
		t.Fatal(err)
	}
	return st
}

func b64(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }

const (
	configNoToken = `{"type":"config","name":"Test","sdkToken":"","ssid":"","dns":[]}`
	configToken   = `{"type":"config","name":"Test","sdkToken":"mgst_test","ssid":"Home","dns":["127.0.0.1"]}`
	unpaired      = `{"type":"status","phase":"unpaired","error":""}`
)

func advertise(name string) string {
	return `{"type":"pair.advertise","name":"` + name + `","service":"7fdd3d1c-38ea-46cf-8b46-314ecf5f240c",` +
		`"rx":"4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01","tx":"d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c",` +
		`"manufacturerId":65535,"manufacturerData":"AA=="}`
}

func pairStatus(state, name string) string {
	return `{"type":"pair.status","state":"` + state + `","name":"` + name + `","error":""}`
}

// openPairing configures musecore with a token and opens pairing, and returns the advertised name.
func openPairing(a *app, dir string) string {
	a.t.Helper()
	a.send(configToken)
	a.expect(unpaired)
	a.send(`{"type":"pair.start"}`)
	line := a.next()
	name := readState(a.t, dir).Identity.BLEName()
	if line != advertise(name) {
		a.t.Fatalf("got  %s\nwant %s", line, advertise(name))
	}
	a.expect(pairStatus("open", name))
	return name
}

func TestConfigWithNoStateIsUnpaired(t *testing.T) {
	dir := t.TempDir()
	a := start(t, dir)
	a.send(configNoToken)
	a.expect(unpaired)
	if rest := a.close(); len(rest) != 0 {
		t.Fatalf("said more on the way out: %q", rest)
	}
	if _, err := os.Stat(filepath.Join(dir, "muse.json")); !os.IsNotExist(err) {
		t.Fatalf("a config wrote the state file: %v", err)
	}
}

func TestUnknownAndMalformedLinesAreIgnored(t *testing.T) {
	a := start(t, t.TempDir())
	a.send(`{"type":"audio","pcm":"AAAA"}`)
	a.send(`not json`)
	a.send(`{"type":"ble.write","data":"!!"}`)
	a.send(configNoToken)
	a.expect(unpaired)
}

func TestPairingNeedsAnSDKToken(t *testing.T) {
	dir := t.TempDir()
	a := start(t, dir)
	a.send(`{"type":"pair.start"}`)
	a.send(configNoToken)
	a.send(`{"type":"pair.start"}`)
	failed := `{"type":"pair.status","state":"failed","name":"","error":"no Muse SDK token: the Muse app will not pair without one"}`
	a.expect(failed, unpaired, failed)
}

func TestPairingAdvertisesAPersistedIdentity(t *testing.T) {
	dir := t.TempDir()
	a := start(t, dir)
	name := openPairing(a, dir)
	if !regexp.MustCompile(`^MuseGadget[0-9A-F]{6}$`).MatchString(name) {
		t.Fatalf("BLE name %q", name)
	}
	id := readState(t, dir).Identity

	a.send(`{"type":"pair.start"}`)
	a.send(`{"type":"pair.stop"}`)
	a.expect(`{"type":"pair.unadvertise"}`, pairStatus("closed", name))

	a.send(`{"type":"pair.start"}`)
	a.expect(advertise(name), pairStatus("open", name))
	if rest := a.close(); !slices.Equal(rest, []string{`{"type":"pair.unadvertise"}`, pairStatus("closed", name)}) {
		t.Fatalf("on the way out: %q", rest)
	}

	again := start(t, dir)
	again.send(configToken)
	again.send(`{"type":"pair.start"}`)
	again.expect(unpaired, advertise(name), pairStatus("open", name))
	if got := readState(t, dir).Identity; got != id {
		t.Fatalf("identity changed across a restart: %v, then %v", id, got)
	}
}

// Without the phone's half of the handshake, a plaintext command that needs a session is as far as
// a write goes: it must reach the Controller, and its answer must come back as a notification.
func TestWritesReachTheControllerAndAnswersComeBackAsNotifications(t *testing.T) {
	dir := t.TempDir()
	a := start(t, dir)
	name := openPairing(a, dir)

	a.send(`{"type":"ble.advertising","ok":true,"error":""}`)
	a.send(`{"type":"ble.connected","device":"phone"}`)
	a.send(fmt.Sprintf(`{"type":"ble.write","data":"%s","mtu":185}`, b64(`{"action":"wifi_scan"}`)))
	a.expectAnyOrder(
		pairStatus("connected", name),
		fmt.Sprintf(`{"type":"ble.notify","packets":["%s"]}`, b64("error_encryption_required")),
	)
	a.send(`{"type":"ble.disconnected"}`)
	a.expect(pairStatus("open", name))
}

func TestAdvertisingFailureClosesPairing(t *testing.T) {
	dir := t.TempDir()
	a := start(t, dir)
	name := openPairing(a, dir)
	a.send(`{"type":"ble.advertising","ok":false,"error":"no adapter"}`)
	a.expect(
		`{"type":"pair.status","state":"failed","name":"`+name+`","error":"could not advertise: no adapter"}`,
		`{"type":"pair.unadvertise"}`,
		pairStatus("closed", name),
	)
}

func TestAskNeedsExactlyOneOfTextAndWAV(t *testing.T) {
	a := start(t, t.TempDir())
	a.send(configNoToken)
	a.expect(unpaired)
	a.send(`{"type":"ask","id":"both","text":"hi","wav":"UklGRg=="}`)
	a.send(`{"type":"ask","id":"neither"}`)
	a.send(`{"type":"ask","id":"text","text":"hi"}`)
	a.expect(
		`{"type":"reply","id":"both","kind":"error","text":"","error":"an ask carries exactly one of text and wav"}`,
		`{"type":"reply","id":"neither","kind":"error","text":"","error":"an ask carries exactly one of text and wav"}`,
		`{"type":"reply","id":"text","kind":"error","text":"","error":"muse: not connected"}`,
	)
}

// A paired state whose API is a closed local port keeps the client running and offline, with no
// network.
func TestUnpairForgetsTheTokensAndKeepsTheIdentity(t *testing.T) {
	dir := t.TempDir()
	id, err := muse.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	paired := muse.State{Identity: id, Credentials: &muse.Credentials{
		AccessToken: "a", RefreshToken: "r", TokenType: "device", Username: "u",
		APIURLV2: "https://127.0.0.1:1", AccessTokenSavedAt: time.Now().Unix(),
	}}
	if err := (muse.FileStore{Path: filepath.Join(dir, "muse.json")}).Save(paired); err != nil {
		t.Fatal(err)
	}
	a := start(t, dir)
	a.send(configNoToken)
	a.expect(`{"type":"status","phase":"connecting","error":""}`)
	var offline struct{ Phase, Error string }
	if err := json.Unmarshal([]byte(a.next()), &offline); err != nil || offline.Phase != "offline" || offline.Error == "" {
		t.Fatalf("want offline with an error, got %+v (%v)", offline, err)
	}

	a.send(`{"type":"ask","id":"1","text":"hi"}`)
	a.expect(`{"type":"reply","id":"1","kind":"error","text":"","error":"muse: not connected"}`)

	a.send(`{"type":"unpair"}`)
	a.expect(unpaired)
	st := readState(t, dir)
	if st.Credentials != nil || st.Identity != id {
		t.Fatalf("after unpair: %+v", st)
	}
	if rest := a.close(); len(rest) != 0 {
		t.Fatalf("said more on the way out: %q", rest)
	}
}
