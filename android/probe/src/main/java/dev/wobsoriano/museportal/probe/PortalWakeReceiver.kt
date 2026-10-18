package dev.wobsoriano.museportal.probe

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Registers "hey muse" with portal-wake (github.com/rudysev/portal-wake), the Vosk wake listener
 * Jarvis uses, and logs each hand-off so its accuracy can be compared with the Vosk check's.
 *
 * portal-wake takes the mic back only after it sees the receiving app record and stop, so this
 * records for a second the way an assistant's turn would.
 */
class PortalWakeReceiver : BroadcastReceiver() {
  @SuppressLint("MissingPermission")
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra("com.portal.wake.extra.ID")
    Log.i(Report.TAG, "portalWake.wake id=$id at=${System.currentTimeMillis()}")
    Toast.makeText(context, "portal-wake heard: hey $id", Toast.LENGTH_SHORT).show()
    val pending = goAsync()
    thread(name = "portal-wake-turn") {
      try {
        val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16_000))
        try {
          rec.startRecording()
          val buf = ShortArray(1_600)
          repeat(10) { rec.read(buf, 0, buf.size) }
          rec.stop()
        } finally {
          rec.release()
        }
      } catch (e: Exception) {
        Log.w(Report.TAG, "portalWake.turn failed: $e")
      } finally {
        pending.finish()
      }
    }
  }
}
