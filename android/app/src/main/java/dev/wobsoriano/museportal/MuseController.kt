package dev.wobsoriano.museportal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.wobsoriano.muse.Command
import dev.wobsoriano.muse.CommandFailed
import dev.wobsoriano.muse.FileStore
import dev.wobsoriano.muse.Handler
import dev.wobsoriano.muse.Level
import dev.wobsoriano.muse.Logger
import dev.wobsoriano.muse.Param
import dev.wobsoriano.muse.ParamType
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.BleEvents
import dev.wobsoriano.muse.core.BlePeripheral
import dev.wobsoriano.muse.core.CoreConfig
import dev.wobsoriano.muse.core.CoreListener
import dev.wobsoriano.muse.core.MuseCore
import dev.wobsoriano.muse.core.PairStatus
import dev.wobsoriano.muse.core.Status
import dev.wobsoriano.museportal.ask.AskEffect
import dev.wobsoriano.museportal.ask.AskInput
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.ask.inFlight
import dev.wobsoriano.museportal.ask.next
import dev.wobsoriano.museportal.audio.Capture
import dev.wobsoriano.museportal.audio.Chime
import dev.wobsoriano.museportal.audio.Microphone
import dev.wobsoriano.museportal.audio.Speaker
import dev.wobsoriano.museportal.ble.GattPeripheral
import dev.wobsoriano.museportal.ble.StandardGattPeripheral
import dev.wobsoriano.museportal.device.AnnouncementInfo
import dev.wobsoriano.museportal.device.AnnouncementStore
import dev.wobsoriano.museportal.device.NetworkInfo
import dev.wobsoriano.museportal.device.Settings
import dev.wobsoriano.museportal.heymuse.WakeHost
import dev.wobsoriano.museportal.heymuse.WakeInput
import dev.wobsoriano.museportal.heymuse.WakeListener
import dev.wobsoriano.museportal.heymuse.WakeState
import dev.wobsoriano.museportal.audio.VoiceOption
import dev.wobsoriano.museportal.setup.Screen
import dev.wobsoriano.museportal.setup.SdkToken
import dev.wobsoriano.museportal.setup.SetupFacts
import dev.wobsoriano.museportal.setup.SetupProgress
import dev.wobsoriano.museportal.setup.screen
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

sealed interface CoreState {
  data object Running : CoreState

  /** The core failed; a fresh one starts in [inMs]. */
  data class Restarting(val error: String, val inMs: Long) : CoreState

  data object Stopped : CoreState
}

/** Everything the screen shows. */
data class MuseState(
    val core: CoreState = CoreState.Stopped,
    /** The last connection status, or null before the first one. */
    val connection: Status? = null,
    /** The last pairing status, or null when pairing was never opened. */
    val pairing: PairStatus? = null,
    /** Whether the core holds a pairing, kept across core restarts; null until it first says. */
    val paired: Boolean? = null,
    val ask: AskState = AskState.Idle,
    /** The answer is being read out. */
    val speaking: Boolean = false,
    val name: String = "",
    val named: Boolean = false,
    val hasToken: Boolean = false,
    /** The token as the screen may show it: `mgst_…` and its last four characters. */
    val tokenHint: String = "",
    val setup: SetupProgress = SetupProgress.NotStarted,
    val canSpeak: Boolean = false,
    /** The voices worth offering; empty when the engine offers no choice. */
    val voices: List<VoiceOption> = emptyList(),
    val voice: String? = null,
    val speechRate: Float = 1f,
    /** The "Hey Muse" switch, and what the listener is doing. */
    val wakeEnabled: Boolean = false,
    val wake: WakeState = WakeState.Off,
    val keepListening: Boolean = false,
    /** Our screen is in front, so the overlay isn't needed. */
    val inFront: Boolean = false,
    val version: String = "",
    /** An image Muse pushed with display.draw_url, shown over the home screen until dismissed. */
    val shownImage: Bitmap? = null,
    /** Announcement audio and spoken replies are silenced; cards still show. */
    val muted: Boolean = false,
    /** The recent announcements the replay button can play again, newest first. */
    val announcements: List<AnnouncementInfo> = emptyList(),
    /** Seconds of recording left before the cap, while a question is being captured; else null. */
    val recordingLeftS: Int? = null,
    /** The fraction of the capture cap left (1 = just started), for the shrinking line; else null. */
    val recordingFraction: Float? = null,
    /** The character range of the answer being spoken right now, when the TTS engine reports it. */
    val spokenRange: IntRange? = null,
) {
  val screen: Screen
    get() = SetupFacts(hasToken, named, paired, setup).screen
}

/**
 * The one owner of the Muse core, the BLE peripheral, the microphone and the screen state. Lives for
 * the whole process; [MuseService] starts and stops it, and with it the "Hey Muse" listener. The core
 * runs in this process: it reaches the BLE peripheral through [GattPeripheral] and the screen through
 * [CoreListener].
 */
class MuseController(private val context: Context) {
  private val settings = Settings(context)
  private val announcementStore = AnnouncementStore(context)
  /** The newest announcement entry, which a following audio.play_url belongs to. */
  @Volatile private var lastEntryId: Long? = null
  @Volatile private var lastEntryAtMs: Long = 0
  /** When the current capture's chunks began, for the on-screen countdown; 0 when not recording. */
  @Volatile private var recordStartMs: Long = 0
  private val _state =
      MutableStateFlow(
          MuseState(
              version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev",
              wakeEnabled = settings.wakeEnabled,
              keepListening = settings.keepListening,
              voice = settings.voice,
              speechRate = settings.speechRate,
              muted = settings.muted,
              announcements = announcementStore.loadAll(),
              setup = settings.setup)
              .withSettings())
  val state: StateFlow<MuseState> = _state.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @Volatile private var core: MuseCore? = null
  private var supervisor: Job? = null

  private val bleEvents =
      object : BleEvents {
        override fun advertising(ok: Boolean, error: String) {
          core?.advertising(ok, error)
        }

        override fun connected(device: String) {
          core?.connected(device)
        }

        override fun wrote(data: ByteArray, mtu: Int) {
          core?.wrote(data, mtu)
        }

        override fun disconnected() {
          core?.disconnected()
        }
      }

  // Android 9 (the Portal) pairs through GattPeripheral's direct binder path; every other
  // device gets the standard GATT server.
  private val ble: BlePeripheral =
      if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) GattPeripheral(context, bleEvents)
      else StandardGattPeripheral(context, bleEvents)

  private val listener =
      object : CoreListener {
        override fun status(status: Status) = _state.update { it.copy(connection = status, paired = status.phase != Phase.Unpaired) }

        override fun pairStatus(status: PairStatus) = _state.update { it.copy(pairing = status) }

        override fun reply(update: AskUpdate) = step(AskInput.Reply(update))
      }

  private val network = NetworkInfo(context) { sendConfig() }
  private val speaker =
      Speaker(
          context,
          settings.voice,
          settings.speechRate,
          onReady = { info -> _state.update { it.copy(canSpeak = info.ready, voices = info.voices) } }) { speaking ->
            this.speaking = speaking
            _state.update { it.copy(speaking = speaking, spokenRange = if (speaking) it.spokenRange else null) }
            if (!speaking) step(AskInput.Spoken(settings.keepListening)) else afterStep()
          }
  private var expiry: Job? = null
  private var expiring: AskState? = null
  private val chime = Chime()
  /** The audio.play_url player; replaced on each play. */
  private var player: MediaPlayer? = null
  private var lastConfig: CoreConfig? = null
  @Volatile private var stopRecording = false
  @Volatile private var speaking = false
  private var busy = false
  private var started = false

  private val heyMuse =
      WakeListener(
          context,
          object : WakeHost {
            override fun wakeAsk() = wake(listenerRecords = true)

            override val shouldStopRecording
              get() = stopRecording

            override fun level(level: Float) = step(AskInput.Level(level))

            override fun recorded(capture: Capture) = this@MuseController.recorded(capture)

            override fun wakeState(state: WakeState) = _state.update { it.copy(wake = state) }
          })

  init {
    speaker.muted = settings.muted
    speaker.onRange = { start, end -> _state.update { it.copy(spokenRange = start..end) } }
  }

  @Synchronized
  fun start() {
    if (started) return
    started = true
    network.start()
    supervisor = scope.launch { supervise() }
    heyMuse.start(settings.wakeEnabled, asking = busy)
  }

  @Synchronized
  fun stop() {
    if (!started) return
    started = false
    heyMuse.stop()
    network.stop()
    supervisor?.cancel()
    supervisor = null
    core?.close()
    core = null
    ble.unadvertise()
    _state.update { it.copy(core = CoreState.Stopped) }
  }

  /**
   * Runs the core, and starts a fresh one with backoff if it ever fails, as the app did when musecore
   * was a process that could die.
   */
  private suspend fun supervise() {
    var failures = 0
    while (true) {
      val c = newCore()
      val startedAt = System.currentTimeMillis()
      try {
        c.run()
        return
      } catch (e: CancellationException) {
        c.close()
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "the Muse core failed", e)
        synchronized(this) { if (core === c) core = null }
        if (System.currentTimeMillis() - startedAt > HEALTHY_MS) failures = 0
        val delayMs = minOf(MAX_BACKOFF_MS, 1000L shl minOf(failures, 5))
        failures++
        _state.update { it.copy(core = CoreState.Restarting(e.message ?: e.toString(), delayMs)) }
        delay(delayMs)
      }
    }
  }

  @Synchronized
  private fun newCore(): MuseCore {
    // A fresh core has no pairing session, so drop whatever the old one left on the air.
    ble.unadvertise()
    _state.update { it.copy(core = CoreState.Running, connection = null, pairing = null) }
    val version = _state.value.version
    val c = MuseCore(FileStore(File(context.filesDir, "muse.json"), ::syncDir), version, listener, ble, logger, commands = gadgetCommands())
    core = c
    val config = currentConfig()
    c.config(config)
    lastConfig = config
    return c
  }

  private fun MuseState.withSettings(): MuseState {
    val token = settings.sdkToken
    return copy(
        name = settings.name,
        named = settings.named,
        hasToken = token.isNotEmpty(),
        tokenHint = if (token.isEmpty()) "" else SdkToken.mask(token))
  }

  fun rename(name: String) {
    if (name.isBlank()) return
    settings.name = name
    _state.update { it.withSettings() }
    sendConfig()
  }

  /** A new token restarts a paired Portal's connection, so it reads as connecting until the core says otherwise. */
  fun saveToken(token: String) {
    val changed = token != settings.sdkToken
    settings.sdkToken = token
    _state.update { s ->
      s.withSettings().let { if (changed && s.paired == true) it.copy(connection = Status(Phase.Connecting)) else it }
    }
    sendConfig()
  }

  /** The setup screens are showing; from now on a restart resumes them rather than going home. */
  fun setupShown() {
    if (settings.setup != SetupProgress.NotStarted) return
    settings.setup = SetupProgress.Started
    _state.update { it.copy(setup = SetupProgress.Started) }
  }

  fun finishSetup() {
    settings.setup = SetupProgress.Finished
    _state.update { it.copy(setup = SetupProgress.Finished) }
  }

  fun setKeepListening(on: Boolean) {
    settings.keepListening = on
    _state.update { it.copy(keepListening = on) }
  }

  /** The home screen's mute switch: silences announcement audio and spoken replies until unmuted. */
  fun setMuted(on: Boolean) {
    settings.muted = on
    speaker.muted = on
    _state.update { it.copy(muted = on) }
    if (on) {
      stopPlayer()
      speaker.stop()
    }
  }

  fun setVoice(name: String?, rate: Float) {
    settings.voice = name
    settings.speechRate = rate
    speaker.choose(name, rate)
    _state.update { it.copy(voice = name, speechRate = rate) }
  }

  fun testVoice() {
    if (_state.value.ask.inFlight) return
    speaker.speak("Hi, I'm Muse. This is how I sound.")
  }

  fun setInFront(inFront: Boolean) {
    _state.update { it.copy(inFront = inFront) }
    if (!inFront) step(AskInput.LeftApp)
  }

  /** What support needs to know, with nothing secret in it. */
  fun diagnostics(): String {
    val s = _state.value
    return listOf(
            "Muse for Portal ${s.version}",
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
            "Name: ${s.name}",
            "Token: ${if (s.hasToken) "set" else "not set"}",
            "Core: ${s.core}",
            "Connection: ${s.connection?.phase ?: "unknown"}${s.connection?.error?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}",
            "Pairing: ${s.pairing?.let { "${it.state} ${it.name} ${it.error}".trim() } ?: "not opened"}",
            "Hey Muse: ${if (s.wakeEnabled) s.wake.toString() else "off"}",
            "Keep listening: ${s.keepListening}",
            "TTS: ${if (s.canSpeak) "ready, ${s.voices.size} voices, voice ${s.voice ?: "default"}, rate ${s.speechRate}" else "none"}",
            "Wi-Fi: ${network.snapshot().ssid.ifEmpty { "unknown" }}",
            "Mic: ${Microphone.lastStats}",
        )
        .joinToString("\n")
  }

  /** Network facts may have changed after a permission grant. */
  fun refreshConfig() = sendConfig()

  fun pair() {
    core?.pairStart()
  }

  fun stopPairing() {
    core?.pairStop()
  }

  fun unpair() {
    core?.unpair()
  }

  fun tapMic() = step(AskInput.Tap)

  /** Closes the turn from the card over other apps; the state goes idle first so no follow-up starts. */
  fun dismiss() {
    step(AskInput.Dismissed)
    speaker.stop()
  }

  fun setWakeEnabled(on: Boolean) {
    settings.wakeEnabled = on
    _state.update { it.copy(wakeEnabled = on) }
    heyMuse.setEnabled(on)
  }

  val wakeEnabled: Boolean
    get() = settings.wakeEnabled

  /**
   * "Hey Muse" was heard: chimes and starts an ask, unless a question is already being recorded.
   * With [listenerRecords] our listener records it on the mic that heard the wake; otherwise (a
   * portal-wake hand-off) the question gets a mic of its own.
   */
  fun wake(listenerRecords: Boolean): Boolean {
    if (_state.value.ask is AskState.Recording) return false
    speaker.stop()
    stopRecording = false
    chime.play()
    step(AskInput.Wake(listenerRecords), skipMs = WakeListener.CHIME_SKIP_MS)
    return true
  }

  fun ask(text: String) {
    val id = UUID.randomUUID().toString()
    val c = core
    step(if (c != null) AskInput.Sent(id).also { c.ask(id, text = text) } else AskInput.NotSent("Muse is not running"))
  }

  @Synchronized
  private fun sendConfig() {
    val c = core ?: return
    val config = currentConfig()
    if (config != lastConfig) {
      c.config(config)
      lastConfig = config
    }
  }

  private fun currentConfig() = CoreConfig(settings.name, settings.sdkToken, network.snapshot().ssid)

  private fun step(input: AskInput, skipMs: Int = 0) {
    var effect: AskEffect? = null
    _state.update { s ->
      val next = s.ask.next(input)
      effect = next.effect
      s.copy(ask = next.state)
    }
    updateCountdown(input)
    when (val e = effect) {
      AskEffect.StartRecording -> record(skipMs)
      AskEffect.ListenForReply -> {
        chime.play()
        record(WakeListener.CHIME_SKIP_MS, waitForSpeechMs = REPLY_WAIT_MS)
      }
      AskEffect.StopRecording -> stopRecording = true
      is AskEffect.Speak -> speaker.speak(e.text)
      null -> Unit
    }
    afterStep()
  }

  /**
   * Keeps the recording countdown honest: it starts at the first level chunk of a capture (the mic
   * loop is already past its skip by then, on both the tap and the wake path) and clears the moment
   * the state leaves [AskState.Recording].
   */
  private fun updateCountdown(input: AskInput) {
    if (_state.value.ask !is AskState.Recording) {
      recordStartMs = 0
      if (_state.value.recordingLeftS != null || _state.value.recordingFraction != null) {
        _state.update { it.copy(recordingLeftS = null, recordingFraction = null) }
      }
      return
    }
    if (input !is AskInput.Level) return
    if (recordStartMs == 0L) recordStartMs = SystemClock.elapsedRealtime()
    val leftMs = recordStartMs + Microphone.MAX_MS - SystemClock.elapsedRealtime()
    val left = kotlin.math.ceil(leftMs / 1000.0).toInt().coerceIn(0, Microphone.MAX_MS / 1000)
    val fraction = (leftMs.toFloat() / Microphone.MAX_MS).coerceIn(0f, 1f)
    val cur = _state.value
    val fractionMoved = cur.recordingFraction == null || kotlin.math.abs(cur.recordingFraction!! - fraction) >= 0.005f
    if (cur.recordingLeftS != left || fractionMoved) {
      _state.update { it.copy(recordingLeftS = left, recordingFraction = fraction) }
    }
  }

  private fun afterStep() {
    updateBusy()
    scheduleExpiry()
  }

  /** A settled answer or a failure goes back to idle after a while once nothing is being said. */
  @Synchronized
  private fun scheduleExpiry() {
    val ask = _state.value.ask
    val resting = !speaking && (ask is AskState.Failed || (ask is AskState.Answered && ask.complete))
    if (resting && ask === expiring) return
    expiry?.cancel()
    expiry = null
    expiring = if (resting) ask else null
    if (!resting) return
    val after = if (ask is AskState.Failed) FAILURE_SHOWN_MS else ANSWER_SHOWN_MS
    expiry =
        scope.launch {
          delay(after)
          step(AskInput.Expired)
        }
  }

  /** Tells the wake listener when an ask starts and when its answer has been spoken. */
  @Synchronized
  private fun updateBusy() {
    val now = _state.value.ask.inFlight || speaking
    if (now == busy) return
    busy = now
    heyMuse.post(WakeInput.Asking(now))
  }

  private fun record(skipMs: Int, waitForSpeechMs: Int = Int.MAX_VALUE) {
    speaker.stop()
    stopPlayer()
    stopRecording = false
    thread(name = "muse-mic") {
      heyMuse.awaitMicFree(MIC_HANDOFF_MS)
      recorded(Microphone.record(skipMs, { stopRecording }, waitForSpeechMs) { level -> step(AskInput.Level(level)) })
    }
  }

  private fun recorded(capture: Capture) {
    // A recording dropped while it ran (the user left the app) is thrown away, not asked.
    if (_state.value.ask !is AskState.Recording) return
    val input =
        when (capture) {
          is Capture.Failed -> AskInput.NotSent(capture.reason)
          is Capture.Wav -> {
            val id = UUID.randomUUID().toString()
            Log.i(TAG, "asking $id with ${capture.bytes.size} bytes of audio")
            val c = core
            if (c != null) AskInput.Sent(id).also { c.ask(id, wav = capture.bytes) } else AskInput.NotSent("Muse is not running")
          }
        }
    step(input)
  }

  // ---- Commands Muse may invoke on this device (advertised at registration) ----

  private fun arg(args: String, key: String): String =
      try {
        JSONObject(args).optString(key, "")
      } catch (e: Exception) {
        ""
      }

  private fun need(args: String, key: String): String =
      arg(args, key).ifEmpty { throw CommandFailed("missing '$key'") }

  /** The command set this app registers, mirroring the ESP32 gadget's surface. */
  private fun gadgetCommands(): List<Command> =
      listOf(
          Command(
              name = "device.health",
              description = "Report this tablet gadget's health: connection, pairing, battery, uptime and the last microphone capture.",
              handler =
                  Handler { _, _ -> healthJson() },
          ),
          Command(
              name = "display.draw_url",
              description = "Download an image (JPEG or PNG) from a URL and show it full-screen on the tablet until dismissed or replaced.",
              required = listOf(Param("url", ParamType.String, "The image URL")),
              timeout = 60.seconds,
              handler =
                  Handler { args, _ ->
                    val url = need(args, "url")
                    val (bmp, bytes) = fetchImage(url)
                    val infos =
                        withContext(Dispatchers.IO) {
                          lastEntryId = announcementStore.saveCard(bytes, url)
                          lastEntryAtMs = System.currentTimeMillis()
                          announcementStore.loadAll()
                        }
                    _state.update { it.copy(shownImage = bmp, announcements = infos) }
                    buildJsonObject {
                      put("shown", true)
                      put("width", bmp.width)
                      put("height", bmp.height)
                    }
                  },
          ),
          Command(
              name = "display.clear",
              description = "Dismiss the image shown by display.draw_url and return to the home screen.",
              handler =
                  Handler { _, _ ->
                    clearImage()
                    buildJsonObject { put("cleared", true) }
                  },
          ),
          Command(
              name = "audio.play_url",
              description = "Play an audio file from a URL through the tablet's speakers. Replaces whatever is playing.",
              required = listOf(Param("url", ParamType.String, "The audio URL")),
              timeout = 60.seconds,
              handler =
                  Handler { args, _ ->
                    val url = need(args, "url")
                    playUrl(url)
                    scope.launch { recordAudio(url) }
                    buildJsonObject {
                      put("playing", url)
                      if (settings.muted) put("muted", true)
                    }
                  },
          ),
          Command(
              name = "audio.stop",
              description = "Stop audio started by audio.play_url.",
              handler =
                  Handler { _, _ ->
                    stopPlayer()
                    buildJsonObject { put("stopped", true) }
                  },
          ),
          Command(
              name = "speaker.speak",
              description = "Speak text aloud with the tablet's own text-to-speech voice.",
              required = listOf(Param("text", ParamType.String, "What to say")),
              handler =
                  Handler { args, _ ->
                    if (!_state.value.canSpeak) throw CommandFailed("text-to-speech is not ready")
                    speaker.speak(need(args, "text"))
                    buildJsonObject { put("spoken", true) }
                  },
          ),
          Command(
              name = "voice.ask",
              description = "Ask Muse a question in words; the answer is shown and spoken on the tablet.",
              required = listOf(Param("text", ParamType.String, "The question")),
              handler =
                  Handler { args, _ ->
                    ask(need(args, "text"))
                    buildJsonObject { put("asked", true) }
                  },
          ),
      )

  private fun healthJson(): JsonObject {
    val s = _state.value
    val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return buildJsonObject {
      put("name", s.name)
      put("app_version", s.version)
      put("android", Build.VERSION.RELEASE)
      put("sdk", Build.VERSION.SDK_INT)
      put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
      put("connection", s.connection?.phase?.label ?: "unknown")
      put("paired", s.paired == true)
      put("wake_enabled", s.wakeEnabled)
      put("speaking", s.speaking)
      if (level >= 0 && scale > 0) put("battery_pct", level * 100 / scale)
      put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
      put("uptime_s", SystemClock.elapsedRealtime() / 1000)
      put("last_mic", Microphone.lastStats)
      put("muted", s.muted)
      put("announcements", s.announcements.size)
    }
  }

  /** Downloads [url]'s bytes, for the image fetch and for saving announcement audio. */
  private suspend fun fetchBytes(url: String): ByteArray =
      withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
          if (c.responseCode != 200) throw CommandFailed("the server answered HTTP ${c.responseCode}")
          c.inputStream.readBytes()
        } finally {
          c.disconnect()
        }
      }

  private suspend fun fetchImage(url: String): Pair<Bitmap, ByteArray> {
    val bytes = fetchBytes(url)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw CommandFailed("that URL did not serve an image")
    return bmp to bytes
  }

  /**
   * Saves a played announcement's audio for the replay list: attached to the card that was just
   * pushed when there is a fresh one, otherwise kept as an audio-only entry. A failed download still
   * records the entry with its URL, which replay then streams.
   */
  private suspend fun recordAudio(url: String) {
    val bytes = runCatching { fetchBytes(url) }.getOrNull()
    val infos =
        withContext(Dispatchers.IO) {
          val last = lastEntryId
          val attached =
              last != null &&
                  System.currentTimeMillis() - lastEntryAtMs < ENTRY_PAIR_WINDOW_MS &&
                  announcementStore.attachAudio(last, bytes, url)
          if (!attached) {
            lastEntryId = announcementStore.saveAudioOnly(bytes, url)
            lastEntryAtMs = System.currentTimeMillis()
          }
          announcementStore.loadAll()
        }
    _state.update { it.copy(announcements = infos) }
  }

  /** The replay button: shows a saved announcement's card again and plays its audio. */
  fun replayAnnouncement(id: Long) {
    scope.launch {
      val entry = withContext(Dispatchers.IO) { announcementStore.entry(id) } ?: return@launch
      entry.cardFile?.let { f ->
        BitmapFactory.decodeFile(f.path)?.let { bmp -> _state.update { it.copy(shownImage = bmp) } }
      }
      val audio = entry.audioFile?.absolutePath ?: entry.audioUrl
      if (audio != null) runCatching { playUrl(audio) }
    }
  }

  fun clearImage() = _state.update { it.copy(shownImage = null) }

  private suspend fun playUrl(url: String) {
    // Muted: the announcement is still recorded for replay, it just isn't heard now.
    if (settings.muted) return
    stopPlayer()
    val mp = MediaPlayer()
    player = mp
    try {
      mp.setDataSource(url)
      suspendCancellableCoroutine { cont ->
        mp.setOnPreparedListener { if (cont.isActive) cont.resume(Unit) }
        mp.setOnErrorListener { _, what, extra ->
          if (cont.isActive) cont.resumeWithException(CommandFailed("the player could not play it (error $what/$extra)"))
          true
        }
        cont.invokeOnCancellation { runCatching { mp.release() } }
        mp.prepareAsync()
      }
      mp.start()
    } catch (e: Exception) {
      runCatching { mp.release() }
      if (player === mp) player = null
      throw e
    }
  }

  private fun stopPlayer() {
    val mp = player ?: return
    player = null
    runCatching { if (mp.isPlaying) mp.stop() }
    runCatching { mp.release() }
  }

  private companion object {
    const val TAG = "MuseApp"
    const val HEALTHY_MS = 60_000L
    const val MAX_BACKOFF_MS = 30_000L
    const val MIC_HANDOFF_MS = 1_000L
    const val REPLY_WAIT_MS = 6_000
    /** A card and the audio that follows it within this window are one announcement. */
    const val ENTRY_PAIR_WINDOW_MS = 120_000L
    const val ANSWER_SHOWN_MS = 20_000L
    const val FAILURE_SHOWN_MS = 8_000L

    val logger = Logger { level, message ->
      when (level) {
        Level.Debug -> Log.d("MuseCore", message)
        Level.Info -> Log.i("MuseCore", message)
        Level.Warn -> Log.w("MuseCore", message)
        Level.Error -> Log.e("MuseCore", message)
      }
    }

    /** Makes a rename in [dir] durable, as musecore's store did by syncing the directory. */
    fun syncDir(dir: File) {
      try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
          Os.fsync(fd)
        } finally {
          Os.close(fd)
        }
      } catch (e: ErrnoException) {
        throw IOException("sync ${dir.path}: ${e.message}", e)
      }
    }
  }
}
