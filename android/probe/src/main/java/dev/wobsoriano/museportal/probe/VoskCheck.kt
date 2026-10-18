package dev.wobsoriano.museportal.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import android.os.Process
import dev.wobsoriano.museportal.wake.vosk.Decision
import dev.wobsoriano.museportal.wake.vosk.IdleReset
import dev.wobsoriano.museportal.wake.vosk.VoskWake
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Listens for "Hey Muse" for [SECONDS] s, following an on-screen script of attempt and no-wake windows, with Vosk in grammar mode and :wake's [VoskWake] rule, fed
 * 100 ms at a time from VOICE_RECOGNITION at 16 kHz mono, as portal-wake does. Reports the model load,
 * every final result, wakes and near misses, what decoding costs, and saves the audio as `vosk.wav` so
 * `tools/wakeeval` can replay it. Not part of a full run: `--es only vosk`.
 *
 * The model is not in the APK: it is pushed to `<external files>/[MODEL]`.
 */
@SuppressLint("MissingPermission")
internal fun voskCheck(context: Context, report: Report, prompt: (String) -> Unit) =
    report.section("vosk") {
      if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        put("recording", "skipped: RECORD_AUDIO denied")
        return@section
      }
      val files = context.getExternalFilesDir(null)
      val dir = File(files, MODEL)
      if (!File(dir, "am").isDirectory || !File(dir, "conf").isDirectory) {
        put("model", "MISSING: push $MODEL to $files/")
        return@section
      }
      val rule = VoskWake.heyMuse()
      LibVosk.setLogLevel(LogLevel.WARNINGS)
      val memory = Memory()
      put("memoryBeforeLoad", memory.now())
      val loadStart = System.nanoTime()
      val model = Model(dir.path)
      val loadMs = (System.nanoTime() - loadStart) / 1_000_000
      try {
        val recStart = System.nanoTime()
        val rec = Recognizer(model, 16_000f, rule.grammarJson)
        rec.setWords(true)
        warmUp(rec)
        put("model", "${dir.name} loadMs=$loadMs recognizerMs=${(System.nanoTime() - recStart) / 1_000_000} " +
            "grammar=${rule.grammar.size} words")
        put("memoryAfterLoad", memory.now())
        try {
          listen(context, rec, rule, memory, prompt)
        } finally {
          rec.close()
        }
      } finally {
        model.close()
      }
    }

private fun Report.Section.listen(context: Context, rec: Recognizer, rule: VoskWake, memory: Memory, prompt: (String) -> Unit) {
  val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
  if (minBuf <= 0) {
    put("recording", "unsupported (getMinBufferSize=$minBuf)")
    return
  }
  val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4 * CHUNK * 2))
  try {
    if (mic.state != AudioRecord.STATE_INITIALIZED) {
      put("recording", "init failed")
      return
    }
    for (s in 5 downTo 1) {
      prompt("Follow the instructions on screen for $SECONDS s. Starting in $s")
      Thread.sleep(1_000)
    }
    SCRIPT.forEachIndexed { i, w ->
      put("script$i", String.format(Locale.ROOT, "%d-%d s %s", w.from, w.from + WINDOW, w.label))
    }
    mic.startRecording()
    if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      put("recording", "startRecording did not start (mic may be held by another app)")
      return
    }

    val buf = ShortArray(CHUNK)
    val pcm = java.io.ByteArrayOutputStream(SECONDS * 32_000)
    val idle = IdleReset()
    var samples = 0L
    var chunks = 0L
    var decodeNs = 0L
    var decodeCpuNs = 0L
    var worstNs = 0L
    var finals = 0
    var emptyFinals = 0
    var resets = 0
    var forcedResets = 0
    var errors = 0
    val wakes = ArrayList<String>()
    val nearMisses = ArrayList<String>()

    fun onFinal(json: String) {
      idle.flushed(samples / 16)
      val decision = rule.decide(json)
      if (decision.heard.isEmpty()) {
        emptyFinals++
        return
      }
      val at = samples / 16_000.0
      val words = decision.heard.joinToString(" ") { String.format(Locale.ROOT, "%s(%.2f)@%.2f", it.word, it.conf, it.start) }
      val line = String.format(Locale.ROOT, "at=%.2fs %s", at, words)
      when (decision) {
        is Decision.Wake -> {
          wakes += line
          prompt(String.format(Locale.ROOT, "WAKE #%d at %.1f s  %s", wakes.size, at, words))
        }
        is Decision.NearMiss -> nearMisses += "$line ${decision.reason}"
        is Decision.Ignore -> {}
      }
      put("final${finals++}", "$line -> ${decision.javaClass.simpleName}")
    }

    val processCpuStart = Process.getElapsedCpuTime()
    val deadline = System.currentTimeMillis() + SECONDS * 1000L
    var nextPrompt = System.currentTimeMillis() + 1_000
    while (System.currentTimeMillis() < deadline) {
      val n = mic.read(buf, 0, CHUNK)
      if (n < 0) {
        if (++errors > 5) {
          put("recording", "read error $n")
          break
        }
        continue
      }
      for (i in 0 until n) {
        pcm.write(buf[i].toInt() and 0xff)
        pcm.write(buf[i].toInt() shr 8 and 0xff)
      }
      samples += n
      val action = idle.decide(samples / 16) { JSONObject(rec.partialResult).optString("partial").isNotBlank() }
      if (action != IdleReset.Action.KEEP) {
        if (action == IdleReset.Action.FORCE_RESET) forcedResets++ else resets++
        rec.reset()
        warmUp(rec)
        idle.flushed(samples / 16)
      }
      val cpu = Debug.threadCpuTimeNanos()
      val t = System.nanoTime()
      val final = rec.acceptWaveForm(buf, n)
      val dt = System.nanoTime() - t
      decodeCpuNs += Debug.threadCpuTimeNanos() - cpu
      decodeNs += dt
      worstNs = maxOf(worstNs, dt)
      chunks++
      memory.sample()
      if (final) onFinal(rec.result)
      val now = System.currentTimeMillis()
      if (now >= nextPrompt) {
        val elapsed = ((now - (deadline - SECONDS * 1000L)) / 1000).toInt()
        val window = SCRIPT.getOrNull(elapsed / WINDOW)
        prompt((window?.instruction ?: "Done") + "\n${WINDOW - elapsed % WINDOW} s   ·   ${wakes.size} wakes so far")
        nextPrompt = now + 1_000
      }
    }
    onFinal(rec.finalResult)
    val processCpuMs = Process.getElapsedCpuTime() - processCpuStart

    val audioSec = samples / 16_000.0
    put("wakes", wakes.size)
    wakes.forEachIndexed { i, w -> put("wake$i", w) }
    put("nearMisses", nearMisses.size)
    nearMisses.forEachIndexed { i, w -> put("nearMiss$i", w) }
    put("finals", "$finals with words, $emptyFinals empty")
    put("idleResets", "$resets quiet, $forcedResets forced")
    if (chunks > 0) {
      put("decode", String.format(Locale.ROOT,
          "real-time factor %.3f (wall), thread CPU %.0f ms per audio s, avg %.1f ms worst %.1f ms per %d ms chunk over %d chunks",
          decodeNs / 1e9 / audioSec, decodeCpuNs / 1e6 / audioSec, decodeNs / 1e6 / chunks, worstNs / 1e6, CHUNK / 16, chunks))
      put("processCpu", String.format(Locale.ROOT, "%.0f ms per audio s (all threads, UI included)", processCpuMs / audioSec))
    }
    put("memoryPeak", memory.peak())
    put("memoryEnd", memory.now() + " pss=${Debug.getPss() / 1024} MB")
    put("audio", String.format(Locale.ROOT, "samples=%d seconds=%.1f routed=%s", samples, audioSec,
        mic.routedDevice?.let { "type${it.type}/${it.productName}" } ?: "unknown"))
    get("wav") { File(context.getExternalFilesDir(null), "vosk.wav").apply { writeWav(this, pcm.toByteArray()) }.path }
  } finally {
    runCatching { mic.stop() }
    mic.release()
    prompt("")
  }
}

/**
 * portal-wake primes the decoder with about a second of silence after creating or resetting it;
 * without it the first word after a reset is dropped.
 */
private fun warmUp(rec: Recognizer) {
  val silence = ShortArray(CHUNK)
  repeat(10) { rec.acceptWaveForm(silence, silence.size) }
}

/** Java heap in use and native heap allocated (where Vosk lives), with their peaks. */
private class Memory {
  private val runtime = Runtime.getRuntime()
  private var peakJava = 0L
  private var peakNative = 0L

  private fun java() = runtime.totalMemory() - runtime.freeMemory()

  fun sample() {
    peakJava = maxOf(peakJava, java())
    peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
  }

  fun now() = "javaHeap=${java() / MB} MB nativeHeap=${Debug.getNativeHeapAllocatedSize() / MB} MB"

  fun peak() = "javaHeap=${peakJava / MB} MB nativeHeap=${peakNative / MB} MB (sampled every chunk)"
}

// The lgraph model cost 3.5x the CPU and twice the memory on a Portal, and was no more accurate.
private const val MODEL = "vosk-model-small-en-us-0.15"

private class Window(val from: Int, val label: String, val instruction: String)

// Alternating windows give each run its own labels: a wake inside an "attempt" window is a hit, one
// inside a "talk" window is a false alarm. tools/wakeeval reads the script lines from the report.
private const val WINDOW = 8
private val SCRIPT =
    listOf("close", "talk", "middle of the room", "talk or TV", "across the room", "talk, say music or news",
        "close", "talk or TV", "across the room", "talk", "middle of the room", "talk, say hey you", "across the room",
        "talk or TV", "close")
        .mapIndexed { i, where ->
          if (i % 2 == 0) Window(i * WINDOW, "attempt $where", "Say \"Hey Muse\" ONCE, $where")
          else Window(i * WINDOW, "no-wake $where", "Don't say it. ${where.replaceFirstChar(Char::uppercase)}")
        }
private val SECONDS = SCRIPT.size * WINDOW
private const val CHUNK = 1_600
private const val MB = 1024 * 1024

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
