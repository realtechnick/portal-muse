package dev.wobsoriano.museportal

import android.content.Context
import android.util.Log
import dev.wobsoriano.museportal.ask.AskEffect
import dev.wobsoriano.museportal.ask.AskInput
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.ask.next
import dev.wobsoriano.museportal.audio.Capture
import dev.wobsoriano.museportal.audio.Microphone
import dev.wobsoriano.museportal.audio.Speaker
import dev.wobsoriano.museportal.ble.GattPeripheral
import dev.wobsoriano.museportal.core.CoreProcess
import dev.wobsoriano.museportal.core.CoreState
import dev.wobsoriano.museportal.device.NetworkInfo
import dev.wobsoriano.museportal.device.Settings
import dev.wobsoriano.museportal.protocol.Command
import dev.wobsoriano.museportal.protocol.Event
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the screen shows. */
data class MuseState(
    val core: CoreState = CoreState.Stopped,
    /** The last `status` from musecore, or null before the first one. */
    val connection: Event.Status? = null,
    /** The last `pair.status`, or null when pairing was never opened. */
    val pairing: Event.PairStatus? = null,
    val ask: AskState = AskState.Idle,
    val name: String = "",
    val hasToken: Boolean = false,
    val canSpeak: Boolean = false,
)

/**
 * The one owner of musecore, the BLE peripheral, the microphone and the screen state. Lives for the
 * whole process; [MuseService] starts and stops it.
 */
class MuseController(private val context: Context) {
  private val settings = Settings(context)
  private val _state = MutableStateFlow(MuseState(name = settings.name, hasToken = settings.sdkToken.isNotEmpty()))
  val state: StateFlow<MuseState> = _state.asStateFlow()

  private val core =
      CoreProcess(
          binary = File(context.applicationInfo.nativeLibraryDir, "libmusecore.so"),
          dataDir = context.filesDir,
          onState = { s -> _state.update { it.copy(core = s) } },
          firstCommand = ::onCoreStarting,
          onEvent = ::onEvent,
      )
  private val ble = GattPeripheral(context) { core.send(it) }
  private val network = NetworkInfo(context) { sendConfig() }
  private val speaker = Speaker(context) { ok -> _state.update { it.copy(canSpeak = ok) } }
  private var lastConfig: Command.Config? = null
  @Volatile private var stopRecording = false
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    network.start()
    core.start()
  }

  @Synchronized
  fun stop() {
    if (!started) return
    started = false
    network.stop()
    core.stop()
    ble.unadvertise()
  }

  fun saveSettings(name: String, sdkToken: String?) {
    settings.name = name
    if (sdkToken != null) settings.sdkToken = sdkToken
    _state.update { it.copy(name = settings.name, hasToken = settings.sdkToken.isNotEmpty()) }
    sendConfig()
  }

  /** Network facts may have changed after a permission grant. */
  fun refreshConfig() = sendConfig()

  fun pair() = core.send(Command.PairStart)

  fun stopPairing() = core.send(Command.PairStop)

  fun unpair() = core.send(Command.Unpair)

  fun tapMic() = step(AskInput.Tap)

  fun ask(text: String) {
    val id = UUID.randomUUID().toString()
    step(if (core.send(Command.Ask.ofText(id, text))) AskInput.Sent(id) else AskInput.NotSent("Muse is not running"))
  }

  @Synchronized
  private fun onCoreStarting(): Command.Config {
    // A fresh musecore has no pairing session, so drop whatever the old one left on the air.
    ble.unadvertise()
    _state.update { it.copy(connection = null, pairing = null) }
    return currentConfig().also { lastConfig = it }
  }

  @Synchronized
  private fun sendConfig() {
    val config = currentConfig()
    if (config != lastConfig && core.send(config)) lastConfig = config
  }

  private fun currentConfig(): Command.Config {
    val net = network.snapshot()
    return Command.Config(settings.name, settings.sdkToken, net.ssid, net.dns)
  }

  private fun onEvent(event: Event) {
    when (event) {
      is Event.Status -> _state.update { it.copy(connection = event) }
      is Event.PairStatus -> _state.update { it.copy(pairing = event) }
      is Event.PairAdvertise -> ble.advertise(event)
      Event.PairUnadvertise -> ble.unadvertise()
      is Event.BleNotify -> ble.notify(event.packets)
      Event.BleDisconnect -> ble.disconnect()
      is Event.Reply -> step(AskInput.Reply(event))
    }
  }

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
              if (core.send(Command.Ask.ofWav(id, capture.bytes))) AskInput.Sent(id)
              else AskInput.NotSent("Muse is not running")
            }
          }
      step(input)
    }
  }

  private companion object {
    const val TAG = "MuseApp"
  }
}
