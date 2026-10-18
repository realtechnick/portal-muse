// Ported from github.com/zserge/microwakeword (microwakeword.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.

/**
 * A streaming wake-word detector compatible with microWakeWord v2 TFLite models
 * (https://github.com/kahrendt/microWakeWord): audio feature extraction (mel-spectrogram),
 * quantised TFLite model inference, and sliding-window probability averaging, in plain Kotlin.
 *
 * ```
 * val det = Detector.create(Config("hey_jarvis.tflite", probabilityCutoff = 0.85, slidingWindowSize = 5, featuresStepMs = 10))
 * for (chunk in audioSource) if (det.processAudio(chunk)) println("wake word detected")
 * ```
 */
package dev.wobsoriano.museportal.wake

import dev.wobsoriano.museportal.wake.audiofrontend.Frontend
import dev.wobsoriano.museportal.wake.audiofrontend.quantizeFeatures
import dev.wobsoriano.museportal.wake.interpreter.Interpreter
import dev.wobsoriano.museportal.wake.model.Model
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.wobsoriano.museportal.wake.audiofrontend.Config as FrontendConfig

/** The parameters for creating a [Detector]. */
data class Config(
    /** The path to a .tflite model file, for [Detector.create]. */
    val modelPath: String = "",
    /** The sliding-window average threshold (0-1). Higher reduces false positives at the cost of sensitivity. */
    val probabilityCutoff: Double,
    /** The number of recent inference results averaged for the detection decision. */
    val slidingWindowSize: Int,
    /** The audio frontend step size in milliseconds. Typical values are 10 or 20. Zero defaults to 20. */
    val featuresStepMs: Int = 20,
)

/**
 * Reads a microWakeWord JSON config, in the micro-wake-word-models convention
 * (`{"micro_wake_word": {"models": [...], "features_step_size": ...}}`), into a [Config]. Like the
 * Go function, it leaves [Config.modelPath] empty.
 */
fun configFromJson(json: String): Config {
  val mww = Json.parseToJsonElement(json).jsonObject["micro_wake_word"]?.jsonObject
  val models = mww?.get("models")?.jsonArray
  require(!models.isNullOrEmpty()) { "no models in JSON" }
  val m = models[0].jsonObject
  val stepMs = mww["features_step_size"]?.jsonPrimitive?.int ?: 0
  return Config(
      probabilityCutoff = m["probability_cutoff"]?.jsonPrimitive?.double ?: 0.0,
      slidingWindowSize = m["sliding_window_size"]?.jsonPrimitive?.int ?: 0,
      featuresStepMs = if (stepMs == 0) 20 else stepMs,
  )
}

/** Called after each model inference; see [Detector.onInference]. */
fun interface InferenceListener {
  fun onInference(invocation: Int, rawProb: Int, slidingAvg: Double)
}

/**
 * A streaming wake-word detector. Not safe for concurrent use.
 *
 * Feeding it allocates nothing once its sample buffer has grown to the largest chunk it is given.
 */
class Detector(config: Config, model: Model) {
  private val cfg = if (config.featuresStepMs == 0) config.copy(featuresStepMs = 20) else config
  private val interp = Interpreter(model)

  /** Frames per inference, from the model input shape [1, stride, 40]. */
  val stride: Int = model.subgraphs[0].let { it.tensors[it.inputs[0]].shape[1] }

  private val fe = Frontend(FrontendConfig.default(cfg.featuresStepMs))
  private val windowSize = fe.windowSize
  private val stepSize = fe.stepSize

  /** The model input being filled, one 40-feature frame at a time: the Go detector's featBuf. */
  private val input = ByteArray(stride * FEATURES)
  private var featCount = 0
  private val probs = IntArray(cfg.slidingWindowSize)
  private var probIdx = 0
  private var invocations = 0

  private var sampleBuf = ShortArray(windowSize * 2)
  private var sampleStart = 0
  private var sampleEnd = 0
  private val raw = IntArray(FEATURES)
  private val frame = ByteArray(FEATURES)

  /**
   * If set, called after each model inference with the invocation count, the raw uint8 probability
   * (0-255), and the current sliding-window average (0-1).
   */
  var onInference: InferenceListener? = null

  /**
   * Feeds 16 kHz, 16-bit signed, mono PCM samples and returns true when the wake word is detected.
   * Any chunk size is accepted; the detector buffers internally.
   *
   * As in the Go detector, a detection returns at once: the window that produced it is not consumed,
   * so the next call runs the front end over it again.
   */
  fun processAudio(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Boolean {
    append(samples, offset, length)
    while (sampleEnd - sampleStart >= windowSize) {
      fe.processFrame(sampleBuf, sampleStart, raw)
      quantizeFeatures(raw, frame)
      if (addFrame(frame)) return true
      sampleStart += stepSize
    }
    return false
  }

  /** Feeds pre-extracted int8 features (40 per frame) directly into the model, bypassing the front end. */
  fun processFeatures(frames: List<ByteArray>): Boolean {
    for (f in frames) if (addFrame(f)) return true
    return false
  }

  /** Feeds one frame of 40 int8 features. */
  fun processFeatures(frame: ByteArray): Boolean = addFrame(frame)

  private fun addFrame(f: ByteArray): Boolean {
    val slot = featCount * FEATURES
    input.fill(0, slot, slot + FEATURES)
    f.copyInto(input, slot, 0, minOf(f.size, FEATURES))
    featCount++
    if (featCount < stride) return false
    featCount = 0
    return runInference()
  }

  private fun runInference(): Boolean {
    interp.setInput(input)
    interp.invoke()
    val prob = interp.outputUint8At0().coerceAtLeast(0)

    probs[probIdx % probs.size] = prob
    probIdx++
    invocations++
    onInference?.onInference(invocations, prob, slidingAverage())
    return isDetected()
  }

  private fun isDetected(): Boolean {
    if (invocations < cfg.slidingWindowSize) return false
    val avg = probs.sum().toDouble() / probs.size.toDouble() / 255.0
    return avg >= cfg.probabilityCutoff
  }

  /** Clears all internal state so the detector can be reused on a new audio stream. The model is kept. */
  fun reset() {
    fe.reset()
    interp.reset()
    featCount = 0
    sampleStart = 0
    sampleEnd = 0
    probs.fill(0)
    probIdx = 0
    invocations = 0
  }

  /** The current sliding-window average probability (0-1). */
  fun slidingAverage(): Double {
    val n = minOf(invocations, probs.size)
    if (n == 0) return 0.0
    return probs.sum().toDouble() / n.toDouble() / 255.0
  }

  private fun append(samples: ShortArray, offset: Int, length: Int) {
    val pending = sampleEnd - sampleStart
    if (sampleEnd + length > sampleBuf.size) {
      val target = if (pending + length > sampleBuf.size) ShortArray(maxOf(sampleBuf.size * 2, pending + length)) else sampleBuf
      sampleBuf.copyInto(target, 0, sampleStart, sampleEnd)
      sampleBuf = target
      sampleStart = 0
      sampleEnd = pending
    }
    samples.copyInto(sampleBuf, sampleEnd, offset, offset + length)
    sampleEnd += length
  }

  companion object {
    private const val FEATURES = 40

    /** Loads the model at [Config.modelPath] and creates a ready-to-use detector. */
    fun create(cfg: Config): Detector = Detector(cfg, Model.loadFile(cfg.modelPath))
  }
}
