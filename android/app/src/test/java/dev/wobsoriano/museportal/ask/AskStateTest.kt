package dev.wobsoriano.museportal.ask

import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.ReplyKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AskStateTest {
  private fun reply(kind: ReplyKind, text: String = "", error: String = "", id: String = "a1") =
      AskInput.Reply(AskUpdate(id, kind, text, error))

  private fun run(vararg inputs: AskInput): List<AskStep> {
    var state: AskState = AskState.Idle
    return inputs.map { input -> state.next(input).also { state = it.state } }
  }

  @Test
  fun aVoiceQuestionGoesFromTapToSpokenAnswer() {
    val steps =
        run(
            AskInput.Tap,
            AskInput.Level(0.4f),
            AskInput.Sent("a1"),
            reply(ReplyKind.Heard, "what time is it"),
            reply(ReplyKind.Text, "It is"),
            reply(ReplyKind.Text, "It is noon."),
            reply(ReplyKind.Settled, "It is noon."),
            reply(ReplyKind.Done, "It is noon."),
        )
    assertEquals(
        listOf(
            AskStep(AskState.Recording(), AskEffect.StartRecording),
            AskStep(AskState.Recording(0.4f)),
            AskStep(AskState.Sending("a1")),
            AskStep(AskState.Heard("a1", "what time is it")),
            AskStep(AskState.Answered("a1", "what time is it", "It is", complete = false)),
            AskStep(AskState.Answered("a1", "what time is it", "It is noon.", complete = false)),
            AskStep(
                AskState.Answered("a1", "what time is it", "It is noon.", complete = true),
                AskEffect.Speak("It is noon.")),
            AskStep(AskState.Answered("a1", "what time is it", "It is noon.", complete = true)),
        ),
        steps)
  }

  @Test
  fun doneWithoutSettledStillSpeaksOnce() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Done, "Hi.")).last()
    assertEquals(AskStep(AskState.Answered("a1", "", "Hi.", complete = true), AskEffect.Speak("Hi.")), last)
  }

  @Test
  fun tappingWhileRecordingStopsIt() {
    assertEquals(
        AskStep(AskState.Recording(), AskEffect.StopRecording), run(AskInput.Tap, AskInput.Tap).last())
  }

  @Test
  fun anErrorReplyFailsTheAsk() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Error, error = "muse: busy")).last()
    assertEquals(AskStep(AskState.Failed("muse: busy")), last)
  }

  @Test
  fun aRecordingThatWasNotSentFails() {
    assertEquals(
        AskStep(AskState.Failed("I didn't hear anything")),
        run(AskInput.Tap, AskInput.NotSent("I didn't hear anything")).last())
  }

  @Test
  fun repliesToAnEarlierAskAreIgnored() {
    val last = run(AskInput.Tap, AskInput.Sent("a2"), reply(ReplyKind.Done, "old answer", id = "a1")).last()
    assertEquals(AskStep(AskState.Sending("a2")), last)
  }

  @Test
  fun aNewTapAfterAnAnswerStartsOver() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Done, "Hi."), AskInput.Tap).last()
    assertEquals(AskStep(AskState.Recording(), AskEffect.StartRecording), last)
  }

  @Test
  fun aTypedQuestionReplacesTheLastAnswer() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Done, "Hi."), AskInput.Sent("a2")).last()
    assertEquals(AskStep(AskState.Sending("a2")), last)
  }

  @Test
  fun aWakeRecordsOnTheListenersMicOrItsOwn() {
    assertEquals(AskStep(AskState.Recording()), run(AskInput.Wake(listenerRecords = true)).last())
    assertEquals(AskStep(AskState.Recording(), AskEffect.StartRecording), run(AskInput.Wake(listenerRecords = false)).last())
  }

  @Test
  fun aWakeWhileRecordingIsIgnored() {
    assertEquals(AskStep(AskState.Recording()), run(AskInput.Tap, AskInput.Wake(listenerRecords = false)).last())
  }

  @Test
  fun anAskIsInFlightUntilItsAnswerIsComplete() {
    val states = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Text, "Hi"), reply(ReplyKind.Done, "Hi.")).map { it.state.inFlight }
    assertEquals(listOf(true, true, true, false), states)
    assertEquals(false, AskState.Failed("no").inFlight)
  }
}
