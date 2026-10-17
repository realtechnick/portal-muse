package dev.wobsoriano.museportal.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.CountDownLatch

/** Only proves a foreground service can start on this device. */
class ProbeService : Service() {
  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_MIN))
    startForeground(
        1,
        Notification.Builder(this, "probe")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Muse probe")
            .build())
    started.countDown()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    @Volatile var started = CountDownLatch(1)
  }
}
