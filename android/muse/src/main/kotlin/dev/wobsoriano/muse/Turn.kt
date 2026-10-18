// Extends linux/src/musegadget/link_client.py in Meta's Muse Gadget SDK, Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE in this
// directory). Modified: rewritten in Kotlin, with reply matching added, and the stored user message's
// text kept as what Muse heard. The SDK's Linux client has no reply matching. This one reads the chat
// events that the SDK's ESP32 firmware reads (esp32/components/muse, under the same license).

package dev.wobsoriano.muse

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bounds what is held while the answer to "which message was ours" is on its way. The events race
 * that answer, so the first few always arrive before it.
 */
internal const val MAX_PENDING_EVENTS = 256
internal const val MAX_REPLY_MESSAGES = 32
internal const val MAX_REPLY_TEXT = 256 shl 10

/**
 * One line of the chat stream. Fields are read loosely, because a field of the wrong type means "not
 * there" to the reference clients and has to mean the same here.
 */
internal class ChatEvent(
    val type: String,
    val event: String,
    private val messageIdField: JsonElement?,
    val payload: Map<String, JsonElement>,
) {
  fun str(key: String) = rawString(payload[key])

  val messageId: String
    get() = str("message_id").ifEmpty { rawString(messageIdField).ifEmpty { str("id") } }

  /** False only when the event says outright that its text is not final yet. */
  val ready: Boolean
    get() = payload["display_text_ready"].let { !(it is JsonPrimitive && !it.isString && it.content == "false") }

  companion object {
    /** Parses one line as Go's json.Unmarshal into the event struct would, or null where it would fail. */
    fun parse(line: String): ChatEvent? {
      val obj = parseObject(line) ?: return null
      val type = looseString(obj["type"]) ?: return null
      val event = looseString(obj["event"]) ?: return null
      val payload =
          when (val p = obj["payload"]) {
            null,
            JsonNull -> emptyMap()
            is JsonObject -> p
            else -> return null
          }
      return ChatEvent(type, event, obj["message_id"], payload)
    }

    /** A string field as Go reads one: absent or null is "", and any other type fails the whole value. */
    private fun looseString(e: JsonElement?): String? =
        when {
          e == null || e == JsonNull -> ""
          e is JsonPrimitive && e.isString -> e.content
          else -> null
        }
  }
}

/** The string a raw JSON value holds, or "" when it is not a string. */
internal fun rawString(raw: JsonElement?): String = if (raw is JsonPrimitive && raw.isString) raw.content else ""

/** Python's idea of true for a JSON value, which is what the reference clients test. */
internal fun truthyJson(raw: JsonElement?): Boolean =
    when (raw) {
      null,
      JsonNull -> false
      is JsonObject -> raw.isNotEmpty()
      is JsonArray -> raw.isNotEmpty()
      is JsonPrimitive -> if (raw.isString) raw.content.isNotEmpty() else raw.content != "false" && raw.content != "0"
    }

internal fun parseObject(text: String): JsonObject? =
    try {
      stateJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
      null
    }

private class ReplyMessage(val id: String) {
  var text = ""
  var done = false
}

/**
 * One question and the answer adding up to it. Not safe for concurrent use; the session's lock
 * guards it.
 *
 * Muse does not link its answer to the question: a reply names no parent, and its text chunks name
 * themselves. So a message that starts after ours belongs to the turn, where "after ours" is after
 * the stream echoes our message, or after the acknowledgment if the echo came first.
 */
internal class Turn(private val now: () -> Long = System::nanoTime) {
  var messageId = ""
    private set

  private var pending = mutableListOf<ChatEvent>()
  private var oursSeen = false
  private val messages = mutableListOf<ReplyMessage>()
  /** Muse saying it is still working, which holds the turn open through a pause. */
  private var busy = false
  var heard = ""
    private set

  var lastEvent = now()
    private set

  /** Poked on every change, for whoever is waiting on the answer. */
  val wake = Channel<Unit>(Channel.CONFLATED)

  fun poke() {
    wake.trySend(Unit)
  }

  /** Takes the next event off the stream. */
  fun add(e: ChatEvent) {
    lastEvent = now()
    try {
      when (e.event) {
        "agent.status",
        "task.status" -> {
          val code = e.payload["activity_code"]
          val status = e.payload["status"]
          if (truthyJson(code)) {
            val activity = rawString(code)
            busy = activity != "online" && activity != "idle"
          } else if (truthyJson(status)) {
            val state = rawString(status)
            busy = state != "completed" && state != "failed"
          }
        }
        "message.user",
        "delta.message_start",
        "delta.text_append",
        "delta.message_done",
        "message.assistant" ->
            if (messageId.isNotEmpty()) apply(e, true) else if (pending.size < MAX_PENDING_EVENTS) pending.add(e)
      }
    } finally {
      poke()
    }
  }

  /** Says which message was ours, and replays what arrived before that was known. */
  fun acknowledged(id: String) {
    messageId = id
    for (e in pending) apply(e, false)
    pending = mutableListOf()
    poke()
  }

  private fun find(id: String) = messages.firstOrNull { it.id == id }

  /**
   * Folds one event into the answer. [afterAck] is whether the event arrived after the
   * acknowledgment, when any new parentless message is taken to follow ours.
   */
  private fun apply(e: ChatEvent, afterAck: Boolean) {
    val id = e.messageId
    if (e.event == "message.user") {
      if (id != messageId) return
      oursSeen = true
      // The stored message carries the words, which for a voice note are the transcript.
      val text = transcript(e.str("display_text"))
      if (text.isNotEmpty() && e.ready) heard = text
      return
    }

    var m = find(id)
    if (m == null) {
      if (e.event == "delta.text_append") return
      val parent = e.str("reply_to_message_id").ifEmpty { e.str("parent_message_id") }
      val linked = parent.isNotEmpty() && (parent == messageId || find(parent) != null)
      val follows = parent.isEmpty() && (oursSeen || afterAck)
      if (!(linked || follows) || messages.size == MAX_REPLY_MESSAGES) return
      m = ReplyMessage(id)
      messages.add(m)
    }

    when (e.event) {
      "delta.message_start" -> Unit
      "delta.text_append" -> m.text = capText(m.text + e.str("text"))
      else -> {
        val final = e.str("display_text").ifEmpty { e.str("content") }
        if (final.isNotEmpty()) m.text = capText(final)
        if (e.event == "delta.message_done" || e.ready) m.done = true
      }
    }
  }

  /** The answer so far: every reply message that has words, a blank line between them. */
  fun text(): String = messages.filter { it.text.isNotEmpty() }.joinToString("\n\n") { it.text }

  /**
   * Whether the answer is complete as far as anyone can tell: there are words, every reply message is
   * done, and Muse is not working on more. Nothing on the stream marks the end of a turn, so this and
   * a pause are all there is.
   */
  fun whole(): Boolean {
    if (busy || messages.isEmpty()) return false
    if (messages.any { !it.done }) return false
    return text().isNotEmpty()
  }
}

/** Cuts on a character boundary, so a runaway reply cannot grow without end. */
internal fun capText(s: String): String {
  val b = s.toByteArray(Charsets.UTF_8)
  if (b.size <= MAX_REPLY_TEXT) return s
  var end = MAX_REPLY_TEXT
  while (end > 0 && (b[end].toInt() and 0xC0) == 0x80) end--
  return String(b, 0, end, Charsets.UTF_8)
}

/**
 * What was said, out of a stored voice note's text: Muse follows the words with a line naming the
 * recording, like "[file:audio/wav workspace/user/files/voice_note-64.wav]".
 */
internal fun transcript(display: String): String =
    display.split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[file:") }.joinToString(" ")
