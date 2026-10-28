package dev.wobsoriano.museportal.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.log10

sealed interface Capture {
  data class Wav(val bytes: ByteArray) : Capture

  data class Failed(val reason: String) : Capture
}

/**
 * Question capture at 16 kHz mono PCM16. Stops after trailing silence, at a 20 s cap, or when
 * [shouldStop] turns true. Leaves out the first [skipMs], where the wake chime plays. Blocks; call it
 * off the main thread.
 *
 * The Portal's mics are loud and close; a tablet across a room is neither. Captured samples are run
 * through a gain stage with a soft knee (see [softGain]) before anything measures or keeps them, so
 * the speech gate, the level meter and the WAV Muse transcribes all hear the boosted signal.
 */
object Microphone {
  private const val TAG = "MuseMic"
  private const val RATE = 16_000

  /**
   * The longest a question may run; the screen counts down from this while recording. 20 s is the
   * Muse service's own ceiling for a voice note — it answers longer ones with "The voice note is
   * too long" — so the cap sits exactly there rather than recording notes that cannot be sent.
   */
  const val MAX_MS = 20_000
  private const val TRAIL_SILENCE_MS = 1_600
  private const val MIN_SPEECH_MS = 300

  /** Speech is a chunk whose peak, after the gain stage, reaches this (≈ −27 dBFS). */
  private const val SPEECH_PEAK = 1_500

  /** How hard quiet audio is pushed: ×8 (≈ +18 dB) below the knee, gentler above it. */
  private const val GAIN = 8
  private const val KNEE = 24_000

  /** The last capture's vital signs, for the diagnostics screen: what the gate heard, and the verdict. */
  @Volatile var lastStats: String = "no capture yet"

  private fun softGain(s: Int): Int {
    val g = s * GAIN
    val a = abs(g)
    if (a <= KNEE) return g
    val over = KNEE + (a - KNEE) / 4
    return (if (g < 0) -over else over).coerceIn(-32_767, 32_767)
  }

  private fun dbfs(amplitude: Int) = "%.1f".format(20 * log10(maxOf(amplitude, 1).toDouble() / 32_768.0))

  /** [waitForSpeechMs]: give up when nobody has started talking by then. */
  fun record(skipMs: Int, shouldStop: () -> Boolean, waitForSpeechMs: Int = MAX_MS, onLevel: (Float) -> Unit): Capture {
    val rec =
        try {
          open(MediaRecorder.AudioSource.MIC) ?: open(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        } catch (e: SecurityException) {
          lastStats = "microphone permission is off"
          return Capture.Failed("Microphone permission is off")
        } catch (e: IllegalArgumentException) {
          null
        }
            ?: run {
              lastStats = "the microphone is not available"
              return Capture.Failed("The microphone is not available")
            }
    try {
      rec.startRecording()
      if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        lastStats = "another app is using the microphone"
        return Capture.Failed("Another app is using the microphone")
      }
      return capture(rec, skipMs, shouldStop, waitForSpeechMs, onLevel)
    } catch (e: IllegalStateException) {
      Log.w(TAG, "recording failed", e)
      lastStats = "another app is using the microphone"
      return Capture.Failed("Another app is using the microphone")
    } finally {
      runCatching { rec.stop() }
      rec.release()
    }
  }

  private fun open(source: Int): AudioRecord? {
    val minBuf =
        AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return null
    val rec =
        AudioRecord(
            source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2))
    if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
    Log.w(TAG, "audio source $source did not initialize")
    rec.release()
    return null
  }

  /** Captures from [rec], which is already recording at 16 kHz mono; the caller stops it. */
  fun capture(
      rec: AudioRecord,
      skipMs: Int,
      shouldStop: () -> Boolean,
      waitForSpeechMs: Int = MAX_MS,
      onLevel: (Float) -> Unit,
  ): Capture {
    val pcm = ByteArrayOutputStream()
    val chunk = ShortArray(RATE / 10)
    var skipped = 0
    while (skipped < skipMs * RATE / 1000 && !shouldStop()) {
      val n = rec.read(chunk, 0, minOf(chunk.size, skipMs * RATE / 1000 - skipped))
      if (n <= 0) break
      skipped += n
    }
    var elapsedMs = 0
    var speechMs = 0
    var silenceMs = 0
    var rawPeak = 0
    while (!shouldStop() && elapsedMs < MAX_MS && (speechMs > 0 || elapsedMs < waitForSpeechMs)) {
      val n = rec.read(chunk, 0, chunk.size)
      if (n <= 0) break
      var peak = 0
      for (i in 0 until n) {
        val s = chunk[i].toInt()
        rawPeak = maxOf(rawPeak, abs(s))
        val v = softGain(s)
        peak = maxOf(peak, abs(v))
        pcm.write(v and 0xff)
        pcm.write((v shr 8) and 0xff)
      }
      onLevel((peak / 24_000f).coerceIn(0f, 1f))
      val ms = n * 1000 / RATE
      elapsedMs += ms
      if (peak >= SPEECH_PEAK) {
        speechMs += ms
        silenceMs = 0
      } else if (speechMs > 0) {
        silenceMs += ms
        if (silenceMs >= TRAIL_SILENCE_MS) break
      }
    }
    if (speechMs < MIN_SPEECH_MS) {
      lastStats = "no speech heard: listened ${elapsedMs}ms, raw peak ${dbfs(rawPeak)} dBFS"
      return Capture.Failed("I didn't hear anything")
    }
    lastStats = "ok: speech ${speechMs}ms of ${elapsedMs}ms, raw peak ${dbfs(rawPeak)} dBFS (gain x$GAIN)"
    return Capture.Wav(Wav.wrap(pcm.toByteArray(), RATE))
  }
}
