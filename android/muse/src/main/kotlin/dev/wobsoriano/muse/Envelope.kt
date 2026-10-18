// Ported from linux/src/musegadget/noise/envelope.py, framing.py and transport.py in Meta's Muse
// Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License,
// Version 2.0 (LICENSE in this directory). Modified: rewritten in Kotlin, with the four frame kinds
// folded into one type.

package dev.wobsoriano.muse

import java.security.SecureRandom

/** The frame kinds, in the order of their field numbers less one. */
internal enum class FrameKind {
  None,
  /** Opens a stream: verb, path, headers, and the start of the body. */
  Request,
  /** Answers one: status, headers, and the start of the body. */
  Response,
  /** More body, either way. */
  Body,
  /** Abandons a stream. */
  Reset,
}

internal data class Header(val key: String, val value: String)

/** One message on one stream of the session. Which fields mean anything depends on [kind]. */
internal class Frame(
    val stream: Long = 0,
    val kind: FrameKind = FrameKind.None,
    val verb: String = "",
    val path: String = "",
    val headers: List<Header> = emptyList(),
    val status: Int = 0,
    val data: ByteArray = EMPTY,
    val endBody: Boolean = false,
    val code: Int = 0,
    val reason: String = "",
) {
  fun withStream(stream: Long) = Frame(stream, kind, verb, path, headers, status, data, endBody, code, reason)

  override fun equals(other: Any?) =
      other is Frame &&
          stream == other.stream &&
          kind == other.kind &&
          verb == other.verb &&
          path == other.path &&
          headers == other.headers &&
          status == other.status &&
          data.contentEquals(other.data) &&
          endBody == other.endBody &&
          code == other.code &&
          reason == other.reason

  override fun hashCode() = stream.hashCode() * 31 + kind.hashCode()

  override fun toString() =
      "Frame(stream=$stream, kind=$kind, verb=$verb, path=$path, headers=$headers, status=$status, " +
          "data=${data.size} bytes, endBody=$endBody, code=$code, reason=$reason)"
}

/** The reset code for a stream its opener gave up on. */
internal const val RESET_CANCELED = 1

private fun ProtoWriter.headers(field: Int, headers: List<Header>) {
  for (h in headers) {
    val hb = ProtoWriter()
    if (h.key.isNotEmpty()) hb.stringField(1, h.key)
    if (h.value.isNotEmpty()) hb.stringField(2, h.value)
    bytesField(field, hb.toByteArray())
  }
}

/** Writes a frame as the server's ServiceFrame message. Fields at their zero value are left out, as proto3 does. */
internal fun encodeFrame(f: Frame): ByteArray {
  val inner = ProtoWriter()
  val field =
      when (f.kind) {
        FrameKind.Request -> {
          if (f.verb.isNotEmpty()) inner.stringField(1, f.verb)
          if (f.path.isNotEmpty()) inner.stringField(2, f.path)
          inner.headers(3, f.headers)
          if (f.data.isNotEmpty()) inner.bytesField(4, f.data)
          if (f.endBody) inner.varintField(5, 1)
          2
        }
        FrameKind.Response -> {
          if (f.status != 0) inner.varintField(1, f.status.toLong())
          inner.headers(2, f.headers)
          if (f.data.isNotEmpty()) inner.bytesField(3, f.data)
          if (f.endBody) inner.varintField(4, 1)
          3
        }
        FrameKind.Body -> {
          if (f.data.isNotEmpty()) inner.bytesField(1, f.data)
          if (f.endBody) inner.varintField(2, 1)
          4
        }
        FrameKind.Reset -> {
          if (f.code != 0) inner.varintField(1, f.code.toLong())
          if (f.reason.isNotEmpty()) inner.stringField(2, f.reason)
          5
        }
        FrameKind.None -> 0
      }
  val out = ProtoWriter()
  if (f.stream != 0L) out.varintField(1, f.stream)
  if (field != 0) out.bytesField(field, inner.toByteArray())
  return out.toByteArray()
}

private fun decodeHeader(b: ByteArray): Header {
  var key = ""
  var value = ""
  val r = ProtoReader(b)
  while (r.next()) {
    when (r.field) {
      1 -> key = r.string(r.wire)
      2 -> value = r.string(r.wire)
      else -> r.skip(r.wire)
    }
  }
  if (r.failed) throw ProtoException()
  return Header(key, value)
}

/** Reads a ServiceFrame. */
internal fun decodeFrame(b: ByteArray): Frame {
  var stream = 0L
  var kind = FrameKind.None
  var verb = ""
  var path = ""
  var headers = mutableListOf<Header>()
  var status = 0
  var data = EMPTY
  var endBody = false
  var code = 0
  var reason = ""
  val r = ProtoReader(b)
  while (r.next()) {
    if (r.field == 1) {
      stream = r.uint(r.wire)
      continue
    }
    if (r.field < 2 || r.field > 5) {
      r.skip(r.wire)
      continue
    }
    // The four kinds are a oneof, so a later one replaces an earlier one whole.
    kind = FrameKind.entries[r.field - 1]
    verb = ""
    path = ""
    headers = mutableListOf()
    status = 0
    data = EMPTY
    endBody = false
    code = 0
    reason = ""
    val inner = ProtoReader(r.bytes(r.wire))
    while (inner.next()) {
      val w = inner.wire
      when {
        kind == FrameKind.Request && inner.field == 1 -> verb = inner.string(w)
        kind == FrameKind.Request && inner.field == 2 -> path = inner.string(w)
        kind == FrameKind.Response && inner.field == 1 -> status = inner.int32(w)
        kind == FrameKind.Request && inner.field == 3 || kind == FrameKind.Response && inner.field == 2 ->
            headers.add(decodeHeader(inner.bytes(w)))
        kind == FrameKind.Request && inner.field == 4 ||
            kind == FrameKind.Response && inner.field == 3 ||
            kind == FrameKind.Body && inner.field == 1 -> data = inner.bytes(w)
        kind == FrameKind.Request && inner.field == 5 ||
            kind == FrameKind.Response && inner.field == 4 ||
            kind == FrameKind.Body && inner.field == 2 -> endBody = inner.uint(w) != 0L
        kind == FrameKind.Reset && inner.field == 1 -> code = inner.int32(w)
        kind == FrameKind.Reset && inner.field == 2 -> reason = inner.string(w)
        else -> inner.skip(w)
      }
    }
    if (inner.failed) throw ProtoException()
  }
  if (r.failed) throw ProtoException()
  return Frame(stream, kind, verb, path, headers, status, data, endBody, code, reason)
}

/**
 * Wraps a frame for the VM's daemon, the service every gadget stream is for and the one a request
 * names by leaving the field out.
 */
internal fun encodeServiceRequest(f: Frame): ByteArray = ProtoWriter().bytesField(2, encodeFrame(f)).toByteArray()

/** Unwraps a frame the VM sent. */
internal fun decodeServiceResponse(b: ByteArray): Frame {
  var payload = EMPTY
  val r = ProtoReader(b)
  while (r.next()) {
    if (r.field == 1) payload = r.bytes(r.wire) else r.skip(r.wire)
  }
  if (r.failed) throw ProtoException()
  if (payload.isEmpty()) throw MuseException("muse: empty response envelope")
  val f = decodeFrame(payload)
  if (f.kind == FrameKind.Request) throw MuseException("muse: the VM sent a request")
  return f
}

/** Leaves room in a 64 KB Noise message for the chunk's own fields and the tag. */
internal const val MAX_CHUNK_PAYLOAD = 65489
internal const val MAX_TOTAL_CHUNKS = 256
internal const val MAX_PENDING_ASSEMBLY = 16
internal const val MAX_ASSEMBLY_BYTES = 16 shl 20
internal const val ASSEMBLY_TTL_NANOS = 60_000_000_000L

private val random = SecureRandom()

private fun randomChunkId(): Long {
  val id = ByteArray(8)
  random.nextBytes(id)
  var v = 0L
  for (i in 7 downTo 0) v = (v shl 8) or (id[i].toLong() and 0xFF)
  return v
}

/**
 * Cuts one envelope into the pieces that each fit a Noise message. Every piece names the envelope
 * with the same random [id], so the far side can put them back together.
 */
internal fun chunk(envelope: ByteArray, id: Long = randomChunkId()): List<ByteArray> {
  val total = maxOf(1, (envelope.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
  if (total > MAX_TOTAL_CHUNKS) throw MuseException("muse: ${envelope.size} bytes is too large to send")
  return (0 until total).map { i ->
    val piece = envelope.copyOfRange(minOf(envelope.size, i * MAX_CHUNK_PAYLOAD), minOf(envelope.size, (i + 1) * MAX_CHUNK_PAYLOAD))
    val b = ProtoWriter()
    if (id != 0L) b.varintField(1, id)
    if (i != 0) b.varintField(2, i.toLong())
    b.varintField(3, total.toLong())
    if (piece.isNotEmpty()) b.bytesField(4, piece)
    b.toByteArray()
  }
}

private class Assembly(val total: Long, val created: Long) {
  val pieces = HashMap<Long, ByteArray>()
  var bytes = 0
}

/**
 * Puts chunked envelopes back together. Any malformed chunk is the end of it, since the stream it
 * belonged to can no longer be trusted to be whole. Not safe for concurrent use.
 */
internal class Assembler(private val now: () -> Long = System::nanoTime) {
  private val pending = HashMap<Long, Assembly>()
  private var poisoned = false

  /** Takes one decrypted chunk and returns the envelope it completes, or null if more are due. */
  fun add(b: ByteArray): ByteArray? {
    if (poisoned) throw MuseException("muse: chunk after a malformed one")
    try {
      return addChunk(b)
    } catch (e: MuseException) {
      poisoned = true
      throw e
    }
  }

  private fun addChunk(b: ByteArray): ByteArray? {
    var id = 0L
    var index = 0L
    var total = 1L
    var payload: ByteArray? = null
    val r = ProtoReader(b)
    while (r.next()) {
      when (r.field) {
        1 -> id = r.uint(r.wire)
        2 -> index = r.uint32(r.wire)
        3 -> total = r.uint32(r.wire)
        4 -> payload = r.bytes(r.wire)
        else -> r.skip(r.wire)
      }
    }
    if (r.failed) throw ProtoException()
    if (total < 1 || total > MAX_TOTAL_CHUNKS || index >= total || (payload?.size ?: 0) > MAX_CHUNK_PAYLOAD) {
      throw MuseException("muse: chunk out of range")
    }
    if (total == 1L) return payload

    val now = now()
    pending.entries.removeAll { now - it.value.created > ASSEMBLY_TTL_NANOS }
    val assembly =
        pending[id]
            ?: run {
              if (pending.size >= MAX_PENDING_ASSEMBLY) throw MuseException("muse: too many unfinished messages")
              Assembly(total, now).also { pending[id] = it }
            }
    if (index in assembly.pieces || assembly.total != total) throw MuseException("muse: chunks disagree")
    assembly.bytes += payload?.size ?: 0
    if (assembly.bytes > MAX_ASSEMBLY_BYTES) throw MuseException("muse: message too large")
    assembly.pieces[index] = payload ?: EMPTY
    if (assembly.pieces.size < total) return null
    pending.remove(id)
    val out = ByteArray(assembly.bytes)
    var at = 0
    for (i in 0 until total) {
      val piece = assembly.pieces.getValue(i)
      piece.copyInto(out, at)
      at += piece.size
    }
    return out
  }
}
