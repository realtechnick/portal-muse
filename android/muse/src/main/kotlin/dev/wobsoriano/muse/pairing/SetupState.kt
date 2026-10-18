package dev.wobsoriano.muse.pairing

/** How far setup has come, for a screen to show. */
enum class SetupState(private val label: String) {
  /** Advertising, no phone has spoken yet. */
  Waiting("waiting for phone"),
  /** A phone is talking, and no session is confirmed. */
  Connected("phone connected"),
  /** The encrypted session is open and the app has consented. */
  Confirmed("confirmed"),
  /** The phone sent the tokens and they are being checked and saved. */
  Provisioning("provisioning"),
  /** The tokens are saved and the phone was told. run returns. */
  Done("done"),
  /**
   * The last attempt failed for [Progress.reason]. Until the window closes the phone may try again,
   * which moves on from here.
   */
  Failed("failed");

  override fun toString() = label
}

/** Why setup is in [SetupState.Failed]. */
enum class Reason(private val label: String) {
  None(""),
  /** The phone sent a record that did not open, or a hello with a bad key. */
  Handshake("the phone and this device could not agree on a secure session"),
  /** The device could not get online. */
  Offline("this device is not online"),
  /** Muse did not accept the device token, or could not be asked. */
  AuthRejected("Muse did not accept this device"),
  /** The tokens could not be saved. */
  Storage("the pairing could not be saved"),
  /** Nobody finished setup in time. */
  WindowClosed("setup timed out"),
  /** The caller stopped setup. */
  Canceled("setup was stopped");

  override fun toString() = label
}

/**
 * One step of setup, as reported to the progress callback. [error] is what went wrong, when there is
 * more to say than [reason]; it never holds a token.
 */
data class Progress(val state: SetupState, val reason: Reason = Reason.None, val error: Throwable? = null)

/** Something that happened that may move the state. */
internal enum class Trigger {
  PhoneWrote,
  PhoneLeft,
  HandshakeBegan,
  PhoneConfirmed,
  ProvisionBegan,
  AttemptFailed,
  Paired,
  WindowClosed,
}

/**
 * Every legal move. A trigger with no entry for the current state changes nothing: a write from a phone
 * that is already connected, or a phone leaving after a failure, which stays on screen until someone
 * tries again.
 */
internal val transitions: Map<SetupState, Map<Trigger, SetupState>> =
    mapOf(
        SetupState.Waiting to mapOf(Trigger.PhoneWrote to SetupState.Connected, Trigger.WindowClosed to SetupState.Failed),
        SetupState.Connected to
            mapOf(
                Trigger.PhoneLeft to SetupState.Waiting,
                Trigger.PhoneConfirmed to SetupState.Confirmed,
                Trigger.AttemptFailed to SetupState.Failed,
                Trigger.WindowClosed to SetupState.Failed),
        SetupState.Confirmed to
            mapOf(
                Trigger.PhoneLeft to SetupState.Waiting,
                Trigger.HandshakeBegan to SetupState.Connected,
                Trigger.ProvisionBegan to SetupState.Provisioning,
                Trigger.AttemptFailed to SetupState.Failed,
                Trigger.WindowClosed to SetupState.Failed),
        SetupState.Provisioning to
            mapOf(
                Trigger.PhoneLeft to SetupState.Waiting,
                Trigger.HandshakeBegan to SetupState.Connected,
                Trigger.Paired to SetupState.Done,
                Trigger.AttemptFailed to SetupState.Failed,
                Trigger.WindowClosed to SetupState.Failed),
        SetupState.Failed to
            mapOf(
                Trigger.HandshakeBegan to SetupState.Connected,
                // The session outlives a failed Wi-Fi join, so the phone may send the tokens again.
                Trigger.ProvisionBegan to SetupState.Provisioning,
                Trigger.AttemptFailed to SetupState.Failed,
                Trigger.WindowClosed to SetupState.Failed),
        SetupState.Done to emptyMap(),
    )
