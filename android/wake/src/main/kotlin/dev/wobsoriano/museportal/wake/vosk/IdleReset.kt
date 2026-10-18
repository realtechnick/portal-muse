package dev.wobsoriano.museportal.wake.vosk

/**
 * When to reset a Vosk recognizer to bound its decode lattice, after portal-wake's IdleResetPolicy.
 *
 * The lattice grows while Vosk does not endpoint (about 3.8 MB a minute on a Portal), and every final
 * result flushes it. A reset also throws away a half-decoded "hey", so it waits for a quiet decoder once
 * [quietMs] has passed without a flush, and only forces one mid-utterance after [forceMs]. Callers pass
 * the time, so this is driven by audio time in the probe and is testable.
 */
class IdleReset(private val quietMs: Long = 25_000, private val forceMs: Long = 60_000) {
  enum class Action { KEEP, RESET, FORCE_RESET }

  init {
    require(quietMs in 1..forceMs) { "need 1 <= quietMs <= forceMs" }
  }

  private var flushedAtMs = 0L

  /** The lattice was just flushed: a final result, a reset or a new recognizer. */
  fun flushed(nowMs: Long) {
    flushedAtMs = nowMs
  }

  /** [midUtterance] is whether the partial result is non-empty; it is only asked once [quietMs] has passed. */
  fun decide(nowMs: Long, midUtterance: () -> Boolean): Action {
    val unflushedMs = nowMs - flushedAtMs
    return when {
      unflushedMs < quietMs -> Action.KEEP
      !midUtterance() -> Action.RESET
      unflushedMs >= forceMs -> Action.FORCE_RESET
      else -> Action.KEEP
    }
  }
}
