package dev.wobsoriano.museportal.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/** Speaks answers when the device has a TTS engine; the Portal may have none. [onSpeaking] follows the answer. */
class Speaker(context: Context, onReady: (Boolean) -> Unit, private val onSpeaking: (Boolean) -> Unit) {
  @Volatile private var ready = false
  /** The answer being spoken; a flushed earlier one reports its end late and must not end this one. */
  @Volatile private var current: String? = null
  private var count = 0
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        Log.i(TAG, if (ready) "TTS ready" else "no TTS engine (status $status)")
        onReady(ready)
      }

  init {
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit

          override fun onDone(utteranceId: String?) = ended(utteranceId)

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) = ended(utteranceId)

          override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
        })
  }

  @Synchronized
  fun speak(text: String) {
    if (!ready) return
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
