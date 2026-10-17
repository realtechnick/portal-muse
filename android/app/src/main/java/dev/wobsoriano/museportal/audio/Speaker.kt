package dev.wobsoriano.museportal.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/** Speaks answers when the device has a TTS engine; the Portal may have none. */
class Speaker(context: Context, onReady: (Boolean) -> Unit) {
  @Volatile private var ready = false
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        Log.i(TAG, if (ready) "TTS ready" else "no TTS engine (status $status)")
        onReady(ready)
      }

  fun speak(text: String) {
    if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
  }

  fun stop() {
    if (ready) tts.stop()
  }

  fun shutdown() = tts.shutdown()

  private companion object {
    const val TAG = "MuseTts"
  }
}
