// Ported from linux/src/musegadget/noise/_proto.py in Meta's Muse Gadget SDK, Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE in this
// directory). Modified: rewritten in Kotlin, keeping only the wire types the Muse messages use.

package dev.wobsoriano.muse

import java.io.ByteArrayOutputStream

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_DELIMITED = 2
internal const val WIRE_FIXED32 = 5

/** A message that does not parse. */
class ProtoException : MuseException("muse: malformed message")

internal class ProtoWriter {
  private val out = ByteArrayOutputStream()

  /** Writes [v] as an unsigned varint: a negative value takes all ten bytes, as Go's uint64 cast does. */
  fun varint(v: Long): ProtoWriter {
    var x = v
    while (x and 0x7FL.inv() != 0L) {
      out.write(((x and 0x7F) or 0x80).toInt())
      x = x ushr 7
    }
    out.write(x.toInt())
    return this
  }

  fun varintField(field: Int, v: Long) = varint((field.toLong() shl 3) or WIRE_VARINT.toLong()).varint(v)

  fun bytesField(field: Int, v: ByteArray): ProtoWriter {
    varint((field.toLong() shl 3) or WIRE_DELIMITED.toLong()).varint(v.size.toLong())
    out.write(v)
    return this
  }

  fun stringField(field: Int, v: String) = bytesField(field, v.toByteArray(Charsets.UTF_8))

  fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Walks the fields of one message. The first malformed byte sets [failed] and ends the walk, so a
 * decoder is a loop over [next] and one check of [failed] after it.
 */
internal class ProtoReader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
  var failed = false
    private set

  var field = 0
    private set

  var wire = 0
    private set

  val remaining
    get() = end - pos

  private fun fail() {
    failed = true
  }

  fun varint(): Long {
    var v = 0L
    for (i in 0 until 10) {
      if (pos >= end) {
        fail()
        return 0
      }
      val c = b[pos++].toInt() and 0xFF
      if (i == 9 && c > 1) {
        fail()
        return 0
      }
      v = v or ((c and 0x7F).toLong() shl (7 * i))
      if (c < 0x80) return v
    }
    fail()
    return 0
  }

  private fun take(n: Long): ByteArray {
    if (n < 0 || n > remaining) {
      fail()
      return EMPTY
    }
    val v = b.copyOfRange(pos, pos + n.toInt())
    pos += n.toInt()
    return v
  }

  /** Moves to the next field, or returns false at the end or on an error. */
  fun next(): Boolean {
    if (failed || pos >= end) return false
    val key = varint()
    if (failed) return false
    val n = key ushr 3
    if (n == 0L || n > (1L shl 29) - 1 || n in 19000L..19999L) {
      fail()
      return false
    }
    field = n.toInt()
    wire = (key and 7).toInt()
    return true
  }

  /** A varint field's value; any other field fails the walk. */
  fun uint(wire: Int): Long {
    if (wire != WIRE_VARINT) {
      fail()
      return 0
    }
    return varint()
  }

  fun bytes(wire: Int): ByteArray {
    if (wire != WIRE_DELIMITED) {
      fail()
      return EMPTY
    }
    val n = varint()
    if (failed) return EMPTY
    return take(n)
  }

  fun string(wire: Int): String {
    val v = bytes(wire)
    if (!isValidUtf8(v)) {
      fail()
      return ""
    }
    return String(v, Charsets.UTF_8)
  }

  /** Passes over a field this code has no use for, which is how newer servers stay compatible. */
  fun skip(wire: Int) {
    when (wire) {
      WIRE_VARINT -> varint()
      WIRE_FIXED64 -> take(8)
      WIRE_DELIMITED -> {
        val n = varint()
        if (!failed) take(n)
      }
      WIRE_FIXED32 -> take(4)
      else -> fail()
    }
  }

  /** Narrows a varint that carries a signed 32-bit value, which the wire sign-extends to 64. */
  fun int32(wire: Int): Int {
    val v = uint(wire)
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) {
      fail()
      return 0
    }
    return v.toInt()
  }

  fun uint32(wire: Int): Long {
    val v = uint(wire)
    if (v < 0 || v > 0xFFFFFFFFL) {
      fail()
      return 0
    }
    return v
  }
}

internal val EMPTY = ByteArray(0)
