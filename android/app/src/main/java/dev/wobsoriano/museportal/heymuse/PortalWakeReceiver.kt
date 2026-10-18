package dev.wobsoriano.museportal.heymuse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.wobsoriano.museportal.MuseService
import dev.wobsoriano.museportal.audio.Microphone
import dev.wobsoriano.museportal.muse
import kotlin.concurrent.thread

/**
 * "Hey Muse" from portal-wake (github.com/rudysev/portal-wake), which listens instead of us when it
 * is installed: the manifest registers the phrase, and on a match portal-wake releases the mic and
 * sends this. It listens again once it has seen us record and stop, which the ask does.
 */
class PortalWakeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    Log.i(TAG, "portal-wake heard ${intent.getStringExtra("com.portal.wake.extra.ID")}")
    MuseService.start(context)
    val muse = context.muse
    if (muse.wakeEnabled) {
      muse.wake(listenerRecords = false)
      return
    }
    // Switched off, but portal-wake still waits for a turn before it listens again; portal-wake
    // finds receivers when packages change, not when a component is disabled, so this one stays on.
    val pending = goAsync()
    thread(name = "portal-wake-turn") {
      try {
        val until = System.currentTimeMillis() + 1_000
        Microphone.record(0, { System.currentTimeMillis() > until }) {}
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    const val TAG = "MuseWake"
  }
}
