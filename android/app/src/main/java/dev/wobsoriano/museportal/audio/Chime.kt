package dev.wobsoriano.museportal.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/** The short tone that says "Hey Muse" was heard and the question is being recorded. */
class Chime {
  private val tone: ToneGenerator? =
      try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
      } catch (e: RuntimeException) {
        Log.w("MuseChime", "no tone generator", e)
        null
      }

  /** Returns at once; the tone lasts [DURATION_MS]. */
  fun play() {
    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
  }

  fun release() {
    tone?.release()
  }

  private companion object {
    const val DURATION_MS = 150
  }
}
