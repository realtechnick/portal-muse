package dev.wobsoriano.museportal.heymuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeFactsTest {
  private fun run(vararg inputs: WakeInput, from: WakeFacts = WakeFacts()) = inputs.fold(from) { f, i -> f.next(i) }

  private val listening =
      run(WakeInput.PortalWake(false), WakeInput.OthersRecording(false), WakeInput.Enabled(true, installed = true), WakeInput.EngineLoaded)

  @Test
  fun offByDefaultAndHoldsNothing() {
    val f = WakeFacts()
    assertEquals(WakeState.Off, f.state)
    assertFalse(f.wantsDownload || f.wantsEngine || f.wantsMic)
  }

  @Test
  fun firstEnableDownloadsThenLoadsThenListens() {
    val enabled = run(WakeInput.Enabled(true, installed = false))
    assertEquals(WakeState.Downloading(null), enabled.state)
    assertTrue(enabled.wantsDownload)
    assertFalse(enabled.wantsMic)

    val halfway = enabled.next(WakeInput.DownloadProgress(0.5f))
    assertEquals(WakeState.Downloading(0.5f), halfway.state)

    val downloaded = halfway.next(WakeInput.Downloaded)
    assertEquals(WakeState.Loading, downloaded.state)
    assertFalse(downloaded.wantsDownload)
    assertTrue("the mic opens before the model loads", downloaded.wantsMic && downloaded.wantsEngine)

    assertEquals(WakeState.Listening, downloaded.next(WakeInput.EngineLoaded).state)
  }

  @Test
  fun anInstalledModelIsNeverDownloadedAgain() {
    val f = run(WakeInput.Enabled(true, installed = true))
    assertFalse(f.wantsDownload)
    assertEquals(WakeState.Loading, f.state)
  }

  @Test
  fun aFailedDownloadWaitsForTheSwitchToRetry() {
    val failed = run(WakeInput.Enabled(true, installed = false), WakeInput.DownloadFailed("HTTP 503"))
    assertEquals(WakeState.Failed("HTTP 503"), failed.state)
    assertFalse(failed.wantsDownload)
    val retried = failed.next(WakeInput.Enabled(false, installed = false)).next(WakeInput.Enabled(true, installed = false))
    assertTrue(retried.wantsDownload)
  }

  @Test
  fun switchingOffMidDownloadCancelsIt() {
    val off = run(WakeInput.Enabled(true, installed = false), WakeInput.DownloadProgress(0.2f), WakeInput.Enabled(false, installed = false))
    assertEquals(WakeState.Off, off.state)
    assertFalse(off.wantsDownload)
    val stopped = off.next(WakeInput.DownloadStopped)
    assertEquals(ModelFile.Missing, stopped.model)
  }

  @Test
  fun anotherAppRecordingTakesTheMicUntilItStops() {
    val yielded = listening.next(WakeInput.OthersRecording(true))
    assertEquals(WakeState.Yielded, yielded.state)
    assertFalse(yielded.wantsMic)
    assertTrue("the model stays loaded", yielded.wantsEngine)
    assertEquals(WakeState.Listening, yielded.next(WakeInput.OthersRecording(false)).state)
  }

  @Test
  fun anAskTakesTheMicUntilTheAnswerIsSpoken() {
    val asking = listening.next(WakeInput.Asking(true))
    assertEquals(WakeState.Asking, asking.state)
    assertFalse(asking.wantsMic)
    assertEquals(WakeState.Listening, asking.next(WakeInput.Asking(false)).state)
  }

  @Test
  fun afterAnAskAnotherAppStillRecordingKeepsUsYielded() {
    val f = listening.next(WakeInput.Asking(true)).next(WakeInput.OthersRecording(true)).next(WakeInput.Asking(false))
    assertEquals(WakeState.Yielded, f.state)
    assertFalse(f.wantsMic)
  }

  @Test
  fun portalWakeListensInsteadOfUs() {
    val f = run(WakeInput.PortalWake(true), WakeInput.Enabled(true, installed = false))
    assertEquals(WakeState.Delegated, f.state)
    assertFalse("no model is needed", f.wantsDownload)
    assertFalse(listening.next(WakeInput.PortalWake(true)).let { it.wantsMic || it.wantsEngine })
    assertTrue(f.next(WakeInput.PortalWake(false)).wantsDownload)
  }

  @Test
  fun aModelThatWillNotLoadIsFetchedAgainOnlyAfterTheSwitch() {
    val broken =
        run(WakeInput.Enabled(true, installed = true), WakeInput.EngineFailed("would not load", modelBroken = true))
    assertEquals(WakeState.Failed("would not load"), broken.state)
    assertFalse(broken.wantsDownload || broken.wantsEngine || broken.wantsMic)
    val retried = broken.next(WakeInput.Enabled(false, installed = false)).next(WakeInput.Enabled(true, installed = false))
    assertTrue(retried.wantsDownload)
    assertEquals(WakeState.Loading, retried.next(WakeInput.Downloaded).state)
  }

  @Test
  fun aMicFailureClearsWhenRecordingChanges() {
    val failed = listening.next(WakeInput.MicFailed("Another app is using the microphone"))
    assertEquals(WakeState.Failed("Another app is using the microphone"), failed.state)
    assertFalse(failed.wantsMic)
    assertTrue(failed.next(WakeInput.OthersRecording(false)).wantsMic)
  }

  @Test
  fun switchingOffFreesTheModelAndTheMic() {
    val off = listening.next(WakeInput.Enabled(false, installed = true))
    assertEquals(WakeState.Off, off.state)
    assertFalse(off.wantsMic || off.wantsEngine)
    assertEquals(WakeState.Loading, off.next(WakeInput.EngineClosed).next(WakeInput.Enabled(true, installed = true)).state)
  }
}
