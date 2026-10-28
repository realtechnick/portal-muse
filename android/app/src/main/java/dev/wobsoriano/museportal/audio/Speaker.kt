package dev.wobsoriano.museportal.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.concurrent.thread

/** A voice the engine both lists and accepts. */
data class VoiceOption(val name: String, val label: String)

/** What the engine offers once it has started; [voices] is empty when it has no voices to choose from. */
data class SpeakerInfo(val ready: Boolean, val voices: List<VoiceOption> = emptyList())

/** Speaks answers when the device has a TTS engine; the Portal may have none. [onSpeaking] follows the answer. */
class Speaker(
    context: Context,
    private var voice: String?,
    private var rate: Float,
    onReady: (SpeakerInfo) -> Unit,
    private val onSpeaking: (Boolean) -> Unit,
) {
  @Volatile private var ready = false
  /** The mute switch: while on, [speak] stays silent. Set by the controller from the user's setting. */
  @Volatile var muted: Boolean = false
  /** The answer being spoken; a flushed earlier one reports its end late and must not end this one. */
  @Volatile private var current: String? = null
  private var count = 0
  private var usable = emptyMap<String, Voice>()
  private var defaultVoice: Voice? = null
  private lateinit var tts: TextToSpeech

  init {
    tts =
        TextToSpeech(context.applicationContext) { status ->
          ready = status == TextToSpeech.SUCCESS
          Log.i(TAG, if (ready) "TTS ready (${tts.defaultEngine})" else "no TTS engine (status $status)")
          // Trying each voice takes the Portal about half a second, so it is kept off the main thread.
          if (ready) thread(name = "muse-tts-voices") { onReady(SpeakerInfo(true, loadVoices())) } else onReady(SpeakerInfo(false))
        }
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit

          override fun onDone(utteranceId: String?) = ended(utteranceId)

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) = ended(utteranceId)

          override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
        })
  }

  /**
   * The voices the engine lists that it also takes: some engines list voices that setVoice refuses or
   * silently ignores, so each is tried. The engine's own default is left in place unless one was chosen.
   */
  @Synchronized
  private fun loadVoices(): List<VoiceOption> {
    val listed =
        runCatching { tts.voices.orEmpty() }
            .onFailure { Log.w(TAG, "the engine would not list its voices", it) }
            .getOrDefault(emptySet())
            .filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
    val default = runCatching { tts.voice }.getOrNull()
    Log.i(TAG, "engine lists ${listed.size} voices; default ${default?.name}: ${listed.joinToString { "${it.name}[${it.locale.toLanguageTag()}]" }}")
    usable = listed.filter { v -> runCatching { tts.setVoice(v) == TextToSpeech.SUCCESS && tts.voice?.name == v.name }.getOrDefault(false) }.associateBy { it.name }
    Log.i(TAG, "engine accepts ${usable.size} of them")
    defaultVoice = default
    apply()
    return if (usable.size > 1) voiceOptions(usable.keys.toList(), default?.name) else emptyList()
  }

  @Synchronized
  fun choose(name: String?, rate: Float) {
    voice = name
    this.rate = rate
    if (ready) apply()
  }

  private fun apply() {
    (voice?.let(usable::get) ?: defaultVoice)?.let { runCatching { tts.setVoice(it) } }
    tts.setSpeechRate(rate)
  }

  @Synchronized
  fun speak(text: String) {
    if (!ready || muted) return
    val id = "answer${++count}"
    current = id
    onSpeaking(true)
    if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) ended(id)
  }

  @Synchronized
  private fun ended(id: String?) {
    if (id == null || id != current) return
    current = null
    onSpeaking(false)
  }

  fun stop() {
    if (ready) tts.stop()
  }

  fun shutdown() = tts.shutdown()

  private companion object {
    const val TAG = "MuseTts"
  }
}

/**
 * The voices as a person can pick them. The Portal's engine names its voices `en_US.44` and the like,
 * with no person's name, so each is its language and region and a number within that region. The
 * [default] voice's region comes first.
 */
fun voiceOptions(names: List<String>, default: String?): List<VoiceOption> {
  data class Parsed(val name: String, val region: String, val index: Int)
  val parsed = names.map { n -> Parsed(n, n.substringBefore('.'), n.substringAfter('.', "").toIntOrNull() ?: Int.MAX_VALUE) }
  val home = default?.substringBefore('.')
  return parsed
      .groupBy { it.region }
      .toSortedMap(compareBy({ it != home }, { regionName(it) }))
      .flatMap { (region, voices) ->
        val sorted = voices.sortedWith(compareBy({ it.index }, { it.name }))
        sorted.mapIndexed { i, v ->
          VoiceOption(v.name, if (sorted.size == 1) regionName(region) else "${regionName(region)} · Voice ${i + 1}")
        }
      }
}

private fun regionName(tag: String): String {
  val parts = tag.split('_', '-')
  val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
  return locale.getDisplayName(Locale.US).ifEmpty { tag }
}
