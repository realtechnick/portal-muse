package dev.wobsoriano.museportal.heymuse

/** What the "Hey Muse" listener is doing, as the screen shows it. */
sealed interface WakeState {
  data object Off : WakeState

  /** [fraction] of the speech model downloaded, or null before the size is known. */
  data class Downloading(val fraction: Float?) : WakeState

  /** Loading the speech model. The mic is already open, so nothing said meanwhile is lost. */
  data object Loading : WakeState

  data object Listening : WakeState

  /** Another app is recording; listening resumes when it stops. */
  data object Yielded : WakeState

  /** A question is being recorded, answered or spoken; listening resumes after. */
  data object Asking : WakeState

  /** portal-wake is installed and listens for "Hey Muse" on our behalf. */
  data object Delegated : WakeState

  /** Turning the switch off and on again retries. */
  data class Failed(val reason: String) : WakeState
}

sealed interface ModelFile {
  data object Missing : ModelFile

  data class Downloading(val fraction: Float?) : ModelFile

  data class Failed(val reason: String) : ModelFile

  data object Installed : ModelFile
}

/** The speech model in memory. */
sealed interface Engine {
  data object Unloaded : Engine

  data object Ready : Engine

  data class Failed(val reason: String) : Engine
}

/**
 * Everything the listener knows, from the user, the system and its own resources. The state and the
 * resources it should hold are functions of these, so no combination can leave the mic held when it
 * should not be.
 */
data class WakeFacts(
    val enabled: Boolean = false,
    val model: ModelFile = ModelFile.Missing,
    val engine: Engine = Engine.Unloaded,
    val portalWake: Boolean = false,
    val othersRecording: Boolean = false,
    val asking: Boolean = false,
    val micError: String? = null,
) {
  val state: WakeState
    get() =
        when {
          !enabled -> WakeState.Off
          portalWake -> WakeState.Delegated
          engine is Engine.Failed -> WakeState.Failed(engine.reason)
          model is ModelFile.Failed -> WakeState.Failed(model.reason)
          model is ModelFile.Downloading -> WakeState.Downloading(model.fraction)
          model == ModelFile.Missing -> WakeState.Downloading(null)
          asking -> WakeState.Asking
          othersRecording -> WakeState.Yielded
          micError != null -> WakeState.Failed(micError)
          engine == Engine.Unloaded -> WakeState.Loading
          else -> WakeState.Listening
        }

  /** Our own listener is wanted; with portal-wake installed it never is. */
  private val ours: Boolean
    get() = enabled && !portalWake

  val wantsDownload: Boolean
    get() = ours && engine !is Engine.Failed && (model == ModelFile.Missing || model is ModelFile.Downloading)

  /** Kept loaded while asking or yielded, so listening resumes at once. */
  val wantsEngine: Boolean
    get() = ours && model == ModelFile.Installed && engine !is Engine.Failed

  /** Opened before the engine loads, so audio buffers meanwhile. */
  val wantsMic: Boolean
    get() = wantsEngine && !asking && !othersRecording && micError == null

  fun next(input: WakeInput): WakeFacts =
      when (input) {
        is WakeInput.Enabled ->
            if (input.on) {
              copy(
                  enabled = true,
                  model =
                      when {
                        input.installed -> ModelFile.Installed
                        model is ModelFile.Downloading -> model
                        else -> ModelFile.Missing
                      },
                  engine = if (engine is Engine.Failed) Engine.Unloaded else engine,
                  micError = null)
            } else {
              copy(enabled = false)
            }
        is WakeInput.PortalWake -> copy(portalWake = input.installed)
        is WakeInput.OthersRecording -> copy(othersRecording = input.yes, micError = null)
        is WakeInput.Asking -> copy(asking = input.yes, micError = null)
        is WakeInput.DownloadProgress ->
            if (model == ModelFile.Missing || model is ModelFile.Downloading) copy(model = ModelFile.Downloading(input.fraction))
            else this
        WakeInput.Downloaded -> copy(model = ModelFile.Installed)
        is WakeInput.DownloadFailed -> copy(model = ModelFile.Failed(input.reason))
        WakeInput.DownloadStopped -> if (model is ModelFile.Downloading) copy(model = ModelFile.Missing) else this
        WakeInput.EngineLoaded -> copy(engine = Engine.Ready)
        is WakeInput.EngineFailed ->
            copy(engine = Engine.Failed(input.reason), model = if (input.modelBroken) ModelFile.Missing else model)
        WakeInput.EngineClosed -> copy(engine = if (engine is Engine.Failed) engine else Engine.Unloaded)
        is WakeInput.MicFailed -> copy(micError = input.reason)
      }
}

sealed interface WakeInput {
  /** The user's switch. [installed] is whether a complete model is on disk. */
  data class Enabled(val on: Boolean, val installed: Boolean) : WakeInput

  data class PortalWake(val installed: Boolean) : WakeInput

  data class OthersRecording(val yes: Boolean) : WakeInput

  /** An ask is being recorded, answered or spoken. */
  data class Asking(val yes: Boolean) : WakeInput

  data class DownloadProgress(val fraction: Float?) : WakeInput

  data object Downloaded : WakeInput

  data class DownloadFailed(val reason: String) : WakeInput

  /** The download was cancelled because it is no longer wanted. */
  data object DownloadStopped : WakeInput

  data object EngineLoaded : WakeInput

  /** [modelBroken]: the files on disk would not load, so they were discarded for a fresh download. */
  data class EngineFailed(val reason: String, val modelBroken: Boolean) : WakeInput

  data object EngineClosed : WakeInput

  data class MicFailed(val reason: String) : WakeInput
}
