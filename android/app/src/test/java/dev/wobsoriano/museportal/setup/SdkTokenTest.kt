package dev.wobsoriano.museportal.setup

import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.core.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkTokenTest {
  private val token = "mgst_AbC123xyz-_.9"

  @Test
  fun aPastedTokenIsTrimmed() {
    assertEquals(token, SdkToken.parse("  $token\n"))
  }

  @Test
  fun somethingThatIsNotATokenIsRefused() {
    assertNull(SdkToken.parse("hello"))
    assertNull(SdkToken.parse("mgst_has a space"))
    assertNull(SdkToken.parse("mgst_"))
    assertNull(SdkToken.parse("MGST_AbC123xyz"))
    assertNull(SdkToken.parse(""))
  }

  @Test
  fun theMaskShowsOnlyThePrefixAndLastFour() {
    assertEquals("mgst_…-_.9", SdkToken.mask(token))
    assertEquals("mgst_…", SdkToken.mask("mgst_ab"))
  }

  @Test
  fun aSavedTokenIsVerifiedByTheConnection() {
    assertEquals(TokenCheck.UntilPairing, savedTokenCheck(paired = false, Status(Phase.Unpaired)))
    assertEquals(TokenCheck.Checking, savedTokenCheck(paired = true, Status(Phase.Connecting)))
    assertEquals(TokenCheck.Accepted, savedTokenCheck(paired = true, Status(Phase.Online)))
    assertEquals(TokenCheck.Offline("timeout"), savedTokenCheck(paired = true, Status(Phase.Offline, "timeout")))
  }
}
