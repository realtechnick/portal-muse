package dev.wobsoriano.museportal

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.wobsoriano.muse.FileStore
import dev.wobsoriano.muse.Level
import dev.wobsoriano.muse.Logger
import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.BleEvents
import dev.wobsoriano.muse.core.CoreConfig
import dev.wobsoriano.muse.core.CoreListener
import dev.wobsoriano.muse.core.MuseCore
import dev.wobsoriano.muse.core.PairStatus
import dev.wobsoriano.muse.core.Status
import dev.wobsoriano.museportal.ask.AskEffect
import dev.wobsoriano.museportal.ask.AskInput
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.ask.next
import dev.wobsoriano.museportal.audio.Capture
import dev.wobsoriano.museportal.audio.Microphone
import dev.wobsoriano.museportal.audio.Speaker
import dev.wobsoriano.museportal.ble.GattPeripheral
import dev.wobsoriano.museportal.device.NetworkInfo
import dev.wobsoriano.museportal.device.Settings
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
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
    val ask: AskState = AskState.Idle,
    val name: String = "",
    val hasToken: Boolean = false,
    val canSpeak: Boolean = false,
)

/**
 * The one owner of the Muse core, the BLE peripheral, the microphone and the screen state. Lives for
 * the whole process; [MuseService] starts and stops it. The core runs in this process: it reaches the
 * BLE peripheral through [GattPeripheral] and the screen through [CoreListener].
 */
class MuseController(private val context: Context) {
  private val settings = Settings(context)
  private val _state = MutableStateFlow(MuseState(name = settings.name, hasToken = settings.sdkToken.isNotEmpty()))
  val state: StateFlow<MuseState> = _state.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @Volatile private var core: MuseCore? = null
  private var supervisor: Job? = null

  private val ble =
      GattPeripheral(
          context,
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
          })

  private val listener =
      object : CoreListener {
        override fun status(status: Status) = _state.update { it.copy(connection = status) }

        override fun pairStatus(status: PairStatus) = _state.update { it.copy(pairing = status) }

        override fun reply(update: AskUpdate) = step(AskInput.Reply(update))
      }

  private val network = NetworkInfo(context) { sendConfig() }
  private val speaker = Speaker(context) { ok -> _state.update { it.copy(canSpeak = ok) } }
  private var lastConfig: CoreConfig? = null
  @Volatile private var stopRecording = false
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    network.start()
    supervisor = scope.launch { supervise() }
  }

  @Synchronized
  fun stop() {
    if (!started) return
    started = false
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
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    val c = MuseCore(FileStore(File(context.filesDir, "muse.json"), ::syncDir), version, listener, ble, logger)
    core = c
    val config = currentConfig()
    c.config(config)
    lastConfig = config
    return c
  }

  fun saveSettings(name: String, sdkToken: String?) {
    settings.name = name
    if (sdkToken != null) settings.sdkToken = sdkToken
    _state.update { it.copy(name = settings.name, hasToken = settings.sdkToken.isNotEmpty()) }
    sendConfig()
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

  private fun step(input: AskInput) {
    var effect: AskEffect? = null
    _state.update { s ->
      val next = s.ask.next(input)
      effect = next.effect
      s.copy(ask = next.state)
    }
    when (val e = effect) {
      AskEffect.StartRecording -> record()
      AskEffect.StopRecording -> stopRecording = true
      is AskEffect.Speak -> speaker.speak(e.text)
      null -> Unit
    }
  }

  private fun record() {
    speaker.stop()
    stopRecording = false
    thread(name = "muse-mic") {
      val input =
          when (val capture = Microphone.record({ stopRecording }) { level -> step(AskInput.Level(level)) }) {
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
  }

  private companion object {
    const val TAG = "MuseApp"
    const val HEALTHY_MS = 60_000L
    const val MAX_BACKOFF_MS = 30_000L

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
