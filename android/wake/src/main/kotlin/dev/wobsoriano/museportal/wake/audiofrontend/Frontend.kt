// Ported from github.com/zserge/microwakeword (audiofrontend/frontend.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.

/**
 * The audio feature extraction pipeline used by microWakeWord models. It converts 16 kHz 16-bit PCM
 * audio into int8 mel-spectrogram features matching TFLite Micro's microfrontend output.
 *
 * The pipeline applies: Hann windowing, FFT, mel filterbank (40 channels, 125-7500 Hz), spectral
 * noise reduction, PCAN gain control, log scaling, and int8 quantisation.
 */
package dev.wobsoriano.museportal.wake.audiofrontend

/** The frontend configuration parameters. */
data class Config(
    val sampleRate: Int,
    val windowSizeMs: Int,
    val stepSizeMs: Int,
    val numChannels: Int,
    val lowerFreq: Double,
    val upperFreq: Double,
) {
  companion object {
    /** The standard MWW frontend configuration. */
    fun default(stepMs: Int) = Config(16000, 30, stepMs, 40, 125.0, 7500.0)
  }
}

/** Processes audio samples into features. Not safe for concurrent use. */
class Frontend(val cfg: Config) {
  val windowSize = cfg.sampleRate * cfg.windowSizeMs / 1000
  val stepSize = cfg.sampleRate * cfg.stepSizeMs / 1000
  private val fftSize = nextPow2(windowSize)
  private val hannWindow = makeHannWindow(windowSize)
  private val melFilters = makeMelFilterbank(cfg.numChannels, fftSize, cfg.sampleRate, cfg.lowerFreq, cfg.upperFreq)
  private val noiseEst = DoubleArray(cfg.numChannels)
  private var firstFrame = true

  // The bins where each filter's weight is not zero. A zero weight adds exactly 0.0 to a sum of
  // non-negative terms, so skipping those bins leaves every sum bit-for-bit what the Go loop makes.
  private val melFirst = IntArray(cfg.numChannels) { ch -> melFilters[ch].indexOfFirst { it != 0.0 }.coerceAtLeast(0) }
  private val melEnd = IntArray(cfg.numChannels) { ch -> melFilters[ch].indexOfLast { it != 0.0 } + 1 }

  private val re = DoubleArray(fftSize)
  private val im = DoubleArray(fftSize)
  private val energy = DoubleArray(fftSize / 2 + 1)
  private val melEnergy = DoubleArray(cfg.numChannels)

  /** Clears the noise estimates. */
  fun reset() {
    firstFrame = true
    noiseEst.fill(0.0)
  }

  /** Features of one window of samples, as uint16 values. Allocates; see the overload that does not. */
  fun processFrame(samples: ShortArray, offset: Int = 0): IntArray = IntArray(cfg.numChannels).also { processFrame(samples, offset, it) }

  /**
   * Writes the uint16 features of the window starting at [offset] into [output], which has
   * numChannels elements. Samples past the end of [samples] are taken as silence.
   */
  fun processFrame(samples: ShortArray, offset: Int, output: IntArray) {
    // 1. Apply Hann window and convert to double
    re.fill(0.0)
    im.fill(0.0)
    var i = 0
    while (i < windowSize && offset + i < samples.size) {
      re[i] = samples[offset + i].toDouble() * hannWindow[i]
      i++
    }

    // 2. FFT, then the power spectrum
    realFFT(re, im, fftSize)
    for (b in energy.indices) energy[b] = re[b] * re[b] + im[b] * im[b]

    // 3. Mel filterbank
    for (ch in 0 until cfg.numChannels) {
      val filter = melFilters[ch]
      var sum = 0.0
      for (bin in melFirst[ch] until melEnd[ch]) sum += energy[bin] * filter[bin]
      melEnergy[ch] = StrictMath.sqrt(maxOf(sum, 0.0))
    }

    // 4. Noise reduction (spectral subtraction)
    noiseReduce(melEnergy)

    // 5. PCAN gain control
    pcanGain(melEnergy)

    // 6. Log scaling, scale_shift = 6
    for (ch in 0 until cfg.numChannels) {
      var v = melEnergy[ch]
      if (v < 1) v = 1.0
      var logV = StrictMath.log(v) * (1 shl 6)
      if (logV < 0) logV = 0.0
      if (logV > 65535) logV = 65535.0
      output[ch] = logV.toInt()
    }
  }

  /** Processes the entire audio buffer and returns int8 features. */
  fun processAllInt8(samples: ShortArray): List<ByteArray> {
    reset()
    val frames = ArrayList<ByteArray>()
    val raw = IntArray(cfg.numChannels)
    var offset = 0
    while (offset + windowSize <= samples.size) {
      processFrame(samples, offset, raw)
      frames += ByteArray(cfg.numChannels).also { quantizeFeatures(raw, it) }
      offset += stepSize
    }
    return frames
  }

  private fun noiseReduce(energy: DoubleArray) {
    val evenSmooth = 0.025
    val oddSmooth = 0.06
    val minSignal = 0.05
    for (i in energy.indices) {
      if (firstFrame) {
        noiseEst[i] = energy[i]
      } else {
        val smooth = if (energy[i] > noiseEst[i]) oddSmooth else evenSmooth
        noiseEst[i] = noiseEst[i] * (1 - smooth) + energy[i] * smooth
      }
      var signal = energy[i] - noiseEst[i]
      val floor = energy[i] * minSignal
      if (signal < floor) signal = floor
      if (signal < 0) signal = 0.0
      energy[i] = signal
    }
    firstFrame = false
  }

  private fun pcanGain(energy: DoubleArray) {
    val strength = 0.95
    val offset = 80.0
    for (i in energy.indices) {
      val denom = StrictMath.pow(noiseEst[i] + offset, strength)
      if (denom > 0) energy[i] = energy[i] / denom * offset
    }
  }
}

/** The int8 conversion of uint16 features that the detector applies (ProcessAudio in microwakeword.go). */
fun quantizeFeatures(raw: IntArray, out: ByteArray) {
  for (i in raw.indices) {
    val v = (raw[i] * 256 + 333) / 666 - 128
    out[i] = v.coerceIn(-128, 127).toByte()
  }
}
