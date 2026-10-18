package dev.wobsoriano.museportal.ask

import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.ReplyKind

/** One question, from the mic to the spoken answer, started by a tap or by "Hey Muse". */
sealed interface AskState {
  data object Idle : AskState

  /** [followUp]: listening for the answer to a question Muse asked, with no wake word. */
  data class Recording(val level: Float = 0f, val followUp: Boolean = false) : AskState

  data class Sending(val id: String) : AskState

  data class Heard(val id: String, val heard: String) : AskState

  /** [complete] once the answer settled or finished; it is spoken then, once. */
  data class Answered(val id: String, val heard: String, val answer: String, val complete: Boolean) :
      AskState

  data class Failed(val reason: String) : AskState
}

sealed interface AskInput {
  data object Tap : AskInput

  /** "Hey Muse" was heard. [listenerRecords]: the wake listener records the question on its own mic. */
  data class Wake(val listenerRecords: Boolean) : AskInput

  data class Level(val level: Float) : AskInput

  /** A recording or a typed question went to the core as ask [id]. */
  data class Sent(val id: String) : AskInput

  data class NotSent(val reason: String) : AskInput

  data class Reply(val reply: AskUpdate) : AskInput

  /** The answer has finished being spoken. */
  data object Spoken : AskInput
}

sealed interface AskEffect {
  data object StartRecording : AskEffect

  data object StopRecording : AskEffect

  data class Speak(val text: String) : AskEffect

  /** Muse asked a question: chime and record the reply, giving up quietly if nobody starts talking. */
  data object ListenForReply : AskEffect
}

/** Whether the question is still being recorded or answered; speaking the answer is tracked apart. */
val AskState.inFlight: Boolean
  get() =
      this is AskState.Recording || this is AskState.Sending || this is AskState.Heard ||
          (this is AskState.Answered && !complete)

data class AskStep(val state: AskState, val effect: AskEffect? = null)

fun AskState.next(input: AskInput): AskStep =
    when (input) {
      AskInput.Tap ->
          if (this is AskState.Recording) AskStep(this, AskEffect.StopRecording)
          else AskStep(AskState.Recording(), AskEffect.StartRecording)
      is AskInput.Wake ->
          if (this is AskState.Recording) AskStep(this)
          else AskStep(AskState.Recording(), if (input.listenerRecords) null else AskEffect.StartRecording)
      is AskInput.Level -> AskStep(if (this is AskState.Recording) copy(level = input.level) else this)
      is AskInput.Sent -> AskStep(AskState.Sending(input.id))
      is AskInput.NotSent ->
          AskStep(
              when {
                this !is AskState.Recording -> this
                followUp -> AskState.Idle
                else -> AskState.Failed(input.reason)
              })
      is AskInput.Reply -> onReply(input.reply)
      // As hey-muse does on the Echo: an answer that ends in a question is listened to without a wake word.
      AskInput.Spoken ->
          if (this is AskState.Answered && complete && answer.trimEnd().endsWith("?")) {
            AskStep(AskState.Recording(followUp = true), AskEffect.ListenForReply)
          } else {
            AskStep(this)
          }
    }

private fun AskState.onReply(reply: AskUpdate): AskStep {
  val asked =
      when (this) {
        is AskState.Sending -> AskState.Answered(id, "", "", complete = false)
        is AskState.Heard -> AskState.Answered(id, heard, "", complete = false)
        is AskState.Answered -> this
        else -> return AskStep(this)
      }
  if (reply.id != asked.id) return AskStep(this)
  return when (reply.kind) {
    ReplyKind.Heard ->
        AskStep(if (this is AskState.Answered) copy(heard = reply.text) else AskState.Heard(asked.id, reply.text))
    ReplyKind.Text -> AskStep(asked.copy(answer = reply.text))
    ReplyKind.Settled,
    ReplyKind.Done -> {
      val text = reply.text.ifEmpty { asked.answer }
      val speak = if (asked.complete || text.isEmpty()) null else AskEffect.Speak(text)
      AskStep(asked.copy(answer = text, complete = true), speak)
    }
    ReplyKind.Error -> AskStep(AskState.Failed(reply.error.ifEmpty { "Muse could not answer" }))
  }
}
