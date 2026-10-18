package dev.wobsoriano.museportal.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupFlowTest {
  private val fresh = SetupFacts(hasToken = false, named = false, paired = false, progress = SetupProgress.NotStarted)

  @Test
  fun aFreshInstallWalksTheStepsInOrder() {
    val token = fresh
    val name = token.copy(hasToken = true, progress = SetupProgress.Started)
    val pair = name.copy(named = true)
    val heyMuse = pair.copy(paired = true)
    val done = heyMuse.copy(progress = SetupProgress.Finished)
    assertEquals(
        listOf(
            Screen.Setup(SetupStep.Token),
            Screen.Setup(SetupStep.Name),
            Screen.Setup(SetupStep.Pair),
            Screen.Setup(SetupStep.HeyMuse),
            Screen.Home),
        listOf(token, name, pair, heyMuse, done).map { it.screen })
  }

  @Test
  fun anInstallPairedBeforeSetupExistedGoesHome() {
    assertEquals(Screen.Home, SetupFacts(hasToken = true, named = false, paired = true, SetupProgress.NotStarted).screen)
  }

  @Test
  fun nothingShowsUntilTheCoreSaysWhetherItIsPaired() {
    assertEquals(Screen.Loading, fresh.copy(hasToken = true, paired = null).screen)
  }

  @Test
  fun theTokenComesFirstEvenBeforeTheCoreReports() {
    assertEquals(Screen.Setup(SetupStep.Token), fresh.copy(paired = null).screen)
  }

  @Test
  fun unpairingAfterSetupReturnsToPairingWithTheNameKept() {
    val unpaired = SetupFacts(hasToken = true, named = true, paired = false, progress = SetupProgress.Finished)
    assertEquals(Screen.Setup(SetupStep.Pair), unpaired.screen)
  }
}
