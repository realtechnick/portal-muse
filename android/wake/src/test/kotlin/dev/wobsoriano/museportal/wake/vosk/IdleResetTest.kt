package dev.wobsoriano.museportal.wake.vosk

import dev.wobsoriano.museportal.wake.vosk.IdleReset.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class IdleResetTest {
  @Test
  fun resetsOnlyWhenUnflushedLongEnoughAndQuiet() {
    val idle = IdleReset(quietMs = 25_000, forceMs = 60_000)
    idle.flushed(1_000)
    assertEquals(Action.KEEP, idle.decide(25_999) { false })
    assertEquals(Action.RESET, idle.decide(26_000) { false })
    assertEquals(Action.KEEP, idle.decide(26_000) { true })
  }

  @Test
  fun forcesAResetMidUtteranceAtTheCap() {
    val idle = IdleReset(quietMs = 25_000, forceMs = 60_000)
    assertEquals(Action.KEEP, idle.decide(59_999) { true })
    assertEquals(Action.FORCE_RESET, idle.decide(60_000) { true })
  }

  @Test
  fun aFinalResultRestartsTheClock() {
    val idle = IdleReset(quietMs = 25_000, forceMs = 60_000)
    idle.flushed(50_000)
    assertEquals(Action.KEEP, idle.decide(70_000) { false })
  }
}
