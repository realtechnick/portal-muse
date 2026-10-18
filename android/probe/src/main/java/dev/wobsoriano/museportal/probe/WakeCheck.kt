package dev.wobsoriano.museportal.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import dev.wobsoriano.museportal.wake.Config
import dev.wobsoriano.museportal.wake.Detector
import dev.wobsoriano.museportal.wake.InferenceListener
import dev.wobsoriano.museportal.wake.model.Model
import kotlin.math.abs
import kotlin.math.log10
import org.json.JSONObject

/**
 * Listens for "Hey Muse" for [SECONDS] s with the Kotlin port of the microWakeWord detector, fed 10 ms
 * at a time from VOICE_RECOGNITION at 16 kHz mono, and reports each detection, the highest
 * probability seen, and what a step costs. Not part of a full run: `--es only wake`.
 *
 * The detector's own cutoff is set so it never fires, and the manifest's cutoff is applied to the
 * sliding average here, as the Echo build does. A detection is one stretch of steps at or above the
 * cutoff; it is reported once, with its peak.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal fun wakeCheck(context: Context, report: Report, prompt: (String) -> Unit) =
    report.section("wake") {
      if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        put("recording", "skipped: RECORD_AUDIO denied")
        return@section
      }
      val micro = JSONObject(context.assets.open("wake/hey_muse.json").bufferedReader().use { it.readText() }).getJSONObject("micro")
      val cutoff = micro.getDouble("probability_cutoff")
      val window = micro.getInt("sliding_window_size")
      val stepMs = micro.getInt("feature_step_size")
      val loadStart = System.nanoTime()
      val model = Model.parse(context.assets.open("wake/hey_muse.tflite").use { it.readBytes() })
      val det = Detector(Config(probabilityCutoff = NEVER_FIRES, slidingWindowSize = window, featuresStepMs = stepMs), model)
      put("model", "hey_muse cutoff=$cutoff window=$window step=${stepMs}ms stride=${det.stride} " +
          "loadMs=${(System.nanoTime() - loadStart) / 1_000_000}")

      val step = 16_000 * stepMs / 1000
      val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (minBuf <= 0) {
        put("recording", "unsupported (getMinBufferSize=$minBuf)")
        return@section
      }
      val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16_000))
      try {
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
          put("recording", "init failed")
          return@section
        }
        for (s in 5 downTo 1) {
          prompt("Say \"Hey Muse\" now and then for $SECONDS s, from across the room. Starting in $s")
          Thread.sleep(1_000)
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
          put("recording", "startRecording did not start (mic may be held by another app)")
          return@section
        }

        var samples = 0L
        var maxRaw = 0
        var maxAvg = 0.0
        var inDetection = false
        var peak = 0.0
        var startedAt = 0L
        val detections = ArrayList<String>()
        det.onInference = InferenceListener { invocation, rawProb, avg ->
          maxRaw = maxOf(maxRaw, rawProb)
          maxAvg = maxOf(maxAvg, avg)
          val hit = invocation >= window && avg >= cutoff
          if (hit && !inDetection) {
            inDetection = true
            startedAt = samples
            peak = avg
          } else if (hit) {
            peak = maxOf(peak, avg)
          } else if (inDetection) {
            inDetection = false
            detections += "t=%.2fs peak=%.4f".format(startedAt / 16_000.0, peak)
          }
        }

        val buf = ShortArray(step)
        val pcm = java.io.ByteArrayOutputStream(SECONDS * 32_000)
        var steps = 0L
        var spentNs = 0L
        var worstNs = 0L
        var allocBytes = 0L
        var loudest = 0
        var errors = 0
        Debug.startAllocCounting()
        val deadline = System.currentTimeMillis() + SECONDS * 1000L
        var nextPrompt = 0L
        while (System.currentTimeMillis() < deadline) {
          val n = rec.read(buf, 0, step)
          if (n < 0) {
            if (++errors > 5) {
              put("recording", "read error $n")
              break
            }
            continue
          }
          for (i in 0 until n) {
            loudest = maxOf(loudest, abs(buf[i].toInt()))
            pcm.write(buf[i].toInt() and 0xff)
            pcm.write(buf[i].toInt() shr 8 and 0xff)
          }
          val allocBefore = Debug.getThreadAllocSize()
          val t = System.nanoTime()
          det.processAudio(buf, 0, n)
          val dt = System.nanoTime() - t
          allocBytes += Debug.getThreadAllocSize() - allocBefore
          samples += n
          steps++
          spentNs += dt
          worstNs = maxOf(worstNs, dt)
          val now = System.currentTimeMillis()
          if (now >= nextPrompt) {
            prompt("Say \"Hey Muse\". ${(deadline - now) / 1000} s left, ${detections.size + if (inDetection) 1 else 0} detections, " +
                "score now %.2f".format(det.slidingAverage()))
            nextPrompt = now + 1_000
          }
        }
        Debug.stopAllocCounting()
        if (inDetection) detections += "t=%.2fs peak=%.4f (still above cutoff at the end)".format(startedAt / 16_000.0, peak)

        put("detections", detections.size)
        detections.forEachIndexed { i, d -> put("detection$i", d) }
        put("maxProbability", "raw %d/255 (%.4f), sliding average %.4f".format(maxRaw, maxRaw / 255.0, maxAvg))
        put("costPerStep", if (steps == 0L) "no steps" else
            "avg %.1f us, worst %.1f us over %d steps of %d ms (%.2f%% of real time)"
                .format(spentNs / 1000.0 / steps, worstNs / 1000.0, steps, stepMs, 100.0 * spentNs / steps / (stepMs * 1_000_000.0)))
        put("allocatedPerStep", if (steps == 0L) "no steps" else "%.1f bytes".format(allocBytes.toDouble() / steps))
        put("audio", "samples=$samples seconds=%.1f peak=%.1f dBFS routed=%s".format(
            samples / 16_000.0, 20 * log10(maxOf(loudest, 1) / 32768.0), rec.routedDevice?.let { "type${it.type}/${it.productName}" } ?: "unknown"))
        // The recording, so a run can be replayed through the Go and Kotlin detectors on the PC.
        get("wav") { java.io.File(context.getExternalFilesDir(null), "wake.wav").apply { writeWav(this, pcm.toByteArray()) }.path }
      } finally {
        runCatching { rec.stop() }
        rec.release()
        prompt("")
      }
    }

private const val SECONDS = 60
private const val NEVER_FIRES = 1.1

private fun writeWav(file: java.io.File, pcm: ByteArray) {
  val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
  header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
  header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
  header.put("data".toByteArray()).putInt(pcm.size)
  file.outputStream().use {
    it.write(header.array())
    it.write(pcm)
  }
}
