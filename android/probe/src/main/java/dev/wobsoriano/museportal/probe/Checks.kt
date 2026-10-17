package dev.wobsoriano.museportal.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** The day-one checks. Each runs on a background thread and reports into [report]. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class Checks(
    private val context: Context,
    private val report: Report,
    /** Shows a prompt on screen, e.g. a countdown before the microphone step. */
    private val prompt: (String) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())

  fun runAll() {
    device()
    permissions()
    packages()
    home()
    tts()
    runtime()
    network()
    exec()
    bluetooth()
    microphones()
  }

  private fun device() =
      report.section("device") {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("hardware", Build.HARDWARE)
        put("board", Build.BOARD)
        put("fingerprint", Build.FINGERPRINT)
        put("display", Build.DISPLAY)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("abis", Build.SUPPORTED_ABIS.joinToString())
        val dm = context.resources.displayMetrics
        put("screen", "${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi} dpi")
      }

  private fun permissions() =
      report.section("permissions") {
        for (p in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)) {
          val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
          put(p.substringAfterLast('.'), if (granted) "granted" else "DENIED")
        }
      }

  private fun packages() =
      report.section("packages") {
        val installed = context.packageManager.getInstalledPackages(0).map { it.packageName }.sorted()
        put("count", installed.size)
        for (name in
            listOf(
                "com.millennium",
                "com.millennium.debug",
                "com.amazon.alexa.multimodal.falcon",
                "com.immortal.launcher")) {
          put(name, if (name in installed) "present" else "absent")
        }
        put("com.facebook.aloha*", installed.filter { it.startsWith("com.facebook.aloha") }.ifEmpty { "none" })
        val words = listOf("aloha", "millennium", "falcon", "tts", "speech", "voice", "hotword")
        put("matching", installed.filter { pkg -> words.any { pkg.contains(it, ignoreCase = true) } })
      }

  /** Whether a sideloaded app can be the home screen, and which apps a launcher would list. */
  private fun home() =
      report.section("home") {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        put("candidates", pm.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName + "/" + it.activityInfo.name })
        get("default") {
          pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { it.packageName + "/" + it.name }
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        put("launchable", pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.distinct().sorted())
      }

  private fun tts() =
      report.section("tts") {
        val ready = CountDownLatch(1)
        var status = Int.MIN_VALUE
        var engine: TextToSpeech? = null
        main.post {
          engine =
              TextToSpeech(context) {
                status = it
                ready.countDown()
              }
        }
        val initialized = ready.await(8, TimeUnit.SECONDS)
        val tts = engine
        put("initStatus", if (!initialized) "timeout" else if (status == TextToSpeech.SUCCESS) "success" else "error $status")
        if (tts != null) {
          get("engines") { tts.engines.map { "${it.name} (${it.label})" }.ifEmpty { "none" } }
          get("defaultEngine") { tts.defaultEngine ?: "none" }
          main.post { tts.shutdown() }
        }
      }

  private fun runtime() =
      report.section("runtime") {
        val am = context.getSystemService(ActivityManager::class.java)
        put("isLowRamDevice", am.isLowRamDevice)
        put("memoryClassMb", am.memoryClass)
        put("largeMemoryClassMb", am.largeMemoryClass)
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        put("totalMemMb", mem.totalMem / 1_048_576)
        put("availMemMb", mem.availMem / 1_048_576)
        get("ignoringBatteryOptimizations") {
          context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
        get("foregroundService") {
          ProbeService.started = CountDownLatch(1)
          context.startForegroundService(Intent(context, ProbeService::class.java))
          val ok = ProbeService.started.await(5, TimeUnit.SECONDS)
          context.stopService(Intent(context, ProbeService::class.java))
          if (ok) "started" else "did not start within 5 s"
        }
      }

  private fun network() =
      report.section("network") {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        put("activeNetwork", active ?: "none")
        val caps = active?.let(cm::getNetworkCapabilities)
        get("transport") {
          when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> caps.toString()
          }
        }
        val lp = active?.let(cm::getLinkProperties)
        get("interface") { lp?.interfaceName }
        get("dnsServers") { lp?.dnsServers?.map { it.hostAddress } }
        get("privateDnsActive") { lp?.isPrivateDnsActive }
        get("privateDnsServerName") { lp?.privateDnsServerName }
        get("ssid") { context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid }
        put("/etc/resolv.conf exists", File("/etc/resolv.conf").exists())
        get("resolve api.muse.ai") {
          var result: Any? = "timeout after 10 s"
          val t = Thread {
            result =
                try {
                  InetAddress.getAllByName("api.muse.ai").map { it.hostAddress }
                } catch (e: Exception) {
                  "failed: $e"
                }
          }
          t.start()
          t.join(10_000)
          result
        }
      }

  // musecore runs as a child process, so the app must be able to fork and exec.
  private fun exec() =
      report.section("exec") {
        val p =
            ProcessBuilder(
                    "/system/bin/sh", "-c", "id; uname -a; getprop net.dns1; getprop ro.build.display.id")
                .redirectErrorStream(true)
                .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        put("exit", p.waitFor())
        out.lines().forEachIndexed { i, line -> put("out$i", line) }
      }

  private fun bluetooth() =
      report.section("bluetooth") {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        put("adapterPresent", adapter != null)
        if (adapter == null) return@section
        put("enabled", adapter.isEnabled)
        if (!adapter.isEnabled) {
          put("enableRequested", adapter.enable())
          put("enabledAfter10s", waitFor(10_000) { adapter.isEnabled })
        }
        get("name") { adapter.name }
        get("address") { adapter.address }
        get("isMultipleAdvertisementSupported") { adapter.isMultipleAdvertisementSupported }
        get("isLe2MPhySupported") { adapter.isLe2MPhySupported }
        get("isLeCodedPhySupported") { adapter.isLeCodedPhySupported }
        get("isLeExtendedAdvertisingSupported") { adapter.isLeExtendedAdvertisingSupported }
        get("leMaximumAdvertisingDataLength") { adapter.leMaximumAdvertisingDataLength }
        val advertiser = adapter.bluetoothLeAdvertiser
        put("bluetoothLeAdvertiser", if (advertiser == null) "null" else "present")

        get("openGattServer") {
          val server = manager.openGattServer(context, object : BluetoothGattServerCallback() {})
          if (server == null) "failed" else "ok".also { server.close() }
        }

        // The app puts the MuseGadget name in the scan response by renaming the adapter.
        get("setName") {
          val original = adapter.name
          val test = "MuseProbe${(1000..9999).random()}"
          val accepted = adapter.setName(test)
          val changed = waitFor(3_000) { adapter.name == test }
          adapter.setName(original)
          val restored = waitFor(3_000) { adapter.name == original }
          "accepted=$accepted changed=$changed restored=$restored"
        }

        if (advertiser != null) {
          get("advertise") {
            val result = CountDownLatch(1)
            var outcome = "no callback within 3 s"
            val callback =
                object : AdvertiseCallback() {
                  override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    outcome = "onStartSuccess (txPower=${settingsInEffect.txPowerLevel}, mode=${settingsInEffect.mode})"
                    result.countDown()
                  }

                  override fun onStartFailure(errorCode: Int) {
                    outcome = "onStartFailure($errorCode ${advertiseError(errorCode)})"
                    result.countDown()
                  }
                }
            val settings =
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build()
            val data =
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(TEST_SERVICE))
                    .addManufacturerData(0xFFFF, byteArrayOf(1, 2, 3, 4, 5, 6))
                    .build()
            val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
            advertiser.startAdvertising(settings, data, scanResponse, callback)
            result.await(3, TimeUnit.SECONDS)
            Thread.sleep(3_000)
            advertiser.stopAdvertising(callback)
            outcome
          }
        }
      }

  private fun microphones() =
      report.section("mic") {
        val am = context.getSystemService(AudioManager::class.java)
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEachIndexed { i, d ->
          put(
              "input$i",
              "type=${inputType(d.type)} product=${d.productName} address=${d.address} " +
                  "channels=${d.channelCounts.toList()} rates=${d.sampleRates.toList()}")
        }
        get("activeRecordings") {
          am.activeRecordingConfigurations.map { "source=${it.clientAudioSource} session=${it.clientAudioSessionId}" }
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
          put("recording", "skipped: RECORD_AUDIO denied")
          return@section
        }
        for (s in 5 downTo 1) {
          prompt("Speak normally from across the room. Recording starts in $s")
          Thread.sleep(1_000)
        }
        for ((sourceName, source) in SOURCES) {
          for (rate in listOf(16_000, 48_000)) {
            for ((channelName, channel) in listOf("mono" to AudioFormat.CHANNEL_IN_MONO, "stereo" to AudioFormat.CHANNEL_IN_STEREO)) {
              prompt("Keep talking. Recording $sourceName $rate Hz $channelName")
              get("$sourceName.$rate.$channelName") { record(source, rate, channel) }
              Thread.sleep(300)
            }
          }
        }
        prompt("")
      }

  private fun record(source: Int, rate: Int, channelMask: Int): String {
    val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return "unsupported (getMinBufferSize=$minBuf)"
    val rec = AudioRecord(source, rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
    try {
      if (rec.state != AudioRecord.STATE_INITIALIZED) return "init failed"
      try {
        rec.startRecording()
      } catch (e: IllegalStateException) {
        return "startRecording threw $e (mic may be held by another app)"
      }
      if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        return "startRecording did not start (mic may be held by another app)"
      }
      val channels = rec.channelCount
      val buf = ShortArray(rate * channels / 10)
      var total = 0L
      var sumSquares = 0.0
      var peak = 0
      var errors = 0
      var firstSoundMs = -1L
      val startedAt = System.currentTimeMillis()
      val deadline = startedAt + 3_000
      while (System.currentTimeMillis() < deadline) {
        val n = rec.read(buf, 0, buf.size)
        if (n < 0) {
          errors++
          if (errors > 5) return "read error $n"
          continue
        }
        for (i in 0 until n) {
          val v = buf[i].toInt()
          sumSquares += v.toDouble() * v
          peak = maxOf(peak, abs(v))
        }
        if (firstSoundMs < 0 && peak > 0) firstSoundMs = System.currentTimeMillis() - startedAt
        total += n
      }
      val routed = rec.routedDevice?.let { "${inputType(it.type)}/${it.productName}" } ?: "unknown"
      if (total == 0L) return "no samples read"
      if (peak == 0) return "all samples zero for 3 s (silenced, or held by another app) routed=$routed"
      val rms = sqrt(sumSquares / total)
      return "ok rms=${dbfs(rms)} dBFS peak=${dbfs(peak.toDouble())} dBFS " +
          "firstSoundMs=$firstSoundMs samples=$total rate=${rec.sampleRate} channels=$channels routed=$routed"
    } finally {
      runCatching { rec.stop() }
      rec.release()
    }
  }

  private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return true
      Thread.sleep(100)
    }
    return condition()
  }

  private companion object {
    val TEST_SERVICE: UUID = UUID.fromString("6d757365-7072-6f62-6500-000000000001")

    val SOURCES =
        listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
        )

    fun dbfs(amplitude: Double) = "%.1f".format(20 * log10(maxOf(amplitude, 1e-9) / 32768.0))

    fun advertiseError(code: Int) =
        when (code) {
          AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
          AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
          AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
          AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
          AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
          else -> "unknown"
        }

    fun inputType(type: Int) =
        when (type) {
          AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
          AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
          AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
          AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
          AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
          AudioDeviceInfo.TYPE_FM_TUNER -> "fm_tuner"
          AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
          AudioDeviceInfo.TYPE_BUS -> "bus"
          AudioDeviceInfo.TYPE_IP -> "ip"
          else -> "type$type"
        }
  }
}
