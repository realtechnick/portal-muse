// This file is a modified port of linux/src/musegadget/ble_framing.py from Meta's Muse Gadget SDK,
// Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.

package dev.wobsoriano.muse.pairing

// Both directions use one frame: 0xFE, chunk index, chunk count, then a piece of the message. A write
// or notification that does not start with 0xFE is a whole message on its own.
private const val CHUNK_MAGIC = 0xFE.toByte()
internal const val HEADER_BYTES = 3
private const val MAX_CHUNKS = 255

/** The most one notification carries, whatever the MTU. */
const val MAX_PACKET = 160
/** The most a reassembled message may hold. */
const val MAX_MESSAGE = 8192
/** What ATT gives before anything is negotiated. */
const val DEFAULT_MTU = 23

class FramingException(message: String) : Exception(message)

/** Splits [data] into framed packets that each fit one notification at [mtu]. */
fun encodeChunks(data: ByteArray, mtu: Int): List<ByteArray> {
  val room = if (mtu > 3) mtu - 3 else 20
  val usable = minOf(room, MAX_PACKET) - HEADER_BYTES
  val total = maxOf(1, (data.size + usable - 1) / usable)
  if (total > MAX_CHUNKS) {
    throw FramingException("pairing: a message of ${data.size} bytes needs $total chunks, more than $MAX_CHUNKS")
  }
  return (0 until total).map { i ->
    byteArrayOf(CHUNK_MAGIC, i.toByte(), total.toByte()) + data.copyOfRange(minOf(i * usable, data.size), minOf((i + 1) * usable, data.size))
  }
}

/**
 * Puts chunked writes back together, strictly in order. Index 0, or a different count, starts a new
 * message; a chunk out of order or a message over [MAX_MESSAGE] throws away what was collected. Not
 * safe for concurrent use.
 */
class Assembler {
  private var buf: java.io.ByteArrayOutputStream? = null
  private var total = 0
  private var next = 0

  /** Forgets a message that is part way in. */
  fun reset() {
    buf = null
    total = 0
    next = 0
  }

  /** Takes one write and returns a message once one is whole, or null. */
  fun feed(packet: ByteArray): ByteArray? {
    if (packet.size < HEADER_BYTES || packet[0] != CHUNK_MAGIC) return packet
    val index = packet[1].toInt() and 0xFF
    val total = packet[2].toInt() and 0xFF
    val piece = packet.copyOfRange(HEADER_BYTES, packet.size)
    if (total == 0) {
      reset()
      return null
    }
    if (index == 0 || total != this.total) {
      reset()
      this.total = total
    }
    if (index != next || index >= this.total || (buf?.size() ?: 0) + piece.size > MAX_MESSAGE) {
      reset()
      return null
    }
    val b = buf ?: java.io.ByteArrayOutputStream().also { buf = it }
    b.write(piece)
    next = index + 1
    if (next < this.total) return null
    val message = b.toByteArray()
    reset()
    return message
  }
}
