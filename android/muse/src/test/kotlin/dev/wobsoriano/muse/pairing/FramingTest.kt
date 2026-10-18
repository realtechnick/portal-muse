package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.assertFails
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramingTest {
  @Test
  fun chunkPayloadFollowsMtu() {
    val message = ByteArray(1000) { 'x'.code.toByte() }
    for ((mtu, perChunk) in mapOf(23 to 17, 185 to 157, 256 to 157, 3 to 17)) {
      val chunks = encodeChunks(message, mtu)
      for ((i, c) in chunks.withIndex()) {
        if (i < chunks.size - 1) assertEquals("mtu $mtu: chunk $i", perChunk, c.size - HEADER_BYTES)
      }
      assertArrayEquals("mtu $mtu", message, chunks.map { it.copyOfRange(HEADER_BYTES, it.size) }.reduce(ByteArray::plus))
    }
  }

  @Test
  fun chunkHeaders() {
    val chunks = encodeChunks(ByteArray(40) { 'a'.code.toByte() }, 23)
    val want = listOf(byteArrayOf(0xFE.toByte(), 0, 3), byteArrayOf(0xFE.toByte(), 1, 3), byteArrayOf(0xFE.toByte(), 2, 3))
    assertEquals(want.size, chunks.size)
    for ((i, c) in chunks.withIndex()) assertArrayEquals("chunk $i header", want[i], c.copyOf(HEADER_BYTES))
  }

  @Test
  fun emptyMessageIsOneChunk() {
    val chunks = encodeChunks(ByteArray(0), DEFAULT_MTU)
    assertEquals(1, chunks.size)
    assertArrayEquals(byteArrayOf(0xFE.toByte(), 0, 1), chunks[0])
    assertArrayEquals(ByteArray(0), Assembler().feed(chunks[0]))
  }

  @Test
  fun tooManyChunksIsAnError() {
    encodeChunks(ByteArray(17 * 255), 23)
    assertFails("256 chunks should not fit") { encodeChunks(ByteArray(17 * 255 + 1), 23) }
  }

  /** The message the last packet completed, if it did. */
  private fun feedAll(a: Assembler, packets: List<ByteArray>): ByteArray? {
    var message: ByteArray? = null
    for (p in packets) message = a.feed(p)
    return message
  }

  @Test
  fun roundTrip() {
    val message = ByteArray(2560) { it.toByte() }
    val chunks = encodeChunks(message, 185)
    val a = Assembler()
    for (c in chunks.dropLast(1)) assertNull("message complete before its last chunk", a.feed(c))
    assertArrayEquals(message, a.feed(chunks.last()))
  }

  @Test
  fun plainWritePassesThrough() {
    val plain = """{"action":"get_device_info"}""".toByteArray()
    assertArrayEquals(plain, Assembler().feed(plain))
  }

  @Test
  fun outOfOrderChunkDiscardsTheMessage() {
    val chunks = encodeChunks(ByteArray(60) { 'y'.code.toByte() }, 23)
    val a = Assembler()
    for (i in listOf(0, 2, 1, 3)) assertNull("chunk $i completed a message that lost its order", a.feed(chunks[i]))
  }

  @Test
  fun indexZeroRestartsTheMessage() {
    val stale = encodeChunks(ByteArray(60) { 'o'.code.toByte() }, 23)
    val fresh = encodeChunks(ByteArray(30) { 'n'.code.toByte() }, 23)
    val a = Assembler()
    a.feed(stale[0])
    a.feed(stale[1])
    assertArrayEquals(ByteArray(30) { 'n'.code.toByte() }, feedAll(a, fresh))
  }

  @Test
  fun changedTotalMidMessageDiscardsIt() {
    val a = Assembler()
    a.feed(byteArrayOf(0xFE.toByte(), 0, 3) + "abc".toByteArray())
    assertNull("a chunk with a new total completed a message", a.feed(byteArrayOf(0xFE.toByte(), 1, 2) + "def".toByteArray()))
    assertNull("the discarded message completed", a.feed(byteArrayOf(0xFE.toByte(), 2, 3) + "ghi".toByteArray()))
  }

  @Test
  fun zeroTotalIsIgnored() {
    assertNull(Assembler().feed(byteArrayOf(0xFE.toByte(), 0, 0) + "abc".toByteArray()))
  }

  @Test
  fun oversizeMessageIsDiscarded() {
    assertNull("a message over MAX_MESSAGE was accepted", feedAll(Assembler(), encodeChunks(ByteArray(MAX_MESSAGE + 1), ASSUMED_MTU)))
    assertEquals(MAX_MESSAGE, feedAll(Assembler(), encodeChunks(ByteArray(MAX_MESSAGE), ASSUMED_MTU))?.size)
  }
}
