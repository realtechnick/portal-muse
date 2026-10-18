package dev.wobsoriano.muse

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Kotlin port against what the Go code produced for the same inputs. The vectors are committed
 * under src/test/resources/vectors; core/internal/muse/vectors_test.go wrote them.
 */
class VectorTest {
  @Test
  fun noiseMatchesFlynnNoise() {
    for (v in Fixtures.json("noise.json").arr.map { it.obj }) {
      val name = v.str("name")
      // flynn/noise draws each ephemeral key as the first 32 bytes of its random source.
      val initiator = NoiseXX(true, DhKey(v.str("initiator_static").unhex()), DetRand("$name initiator ephemeral"))
      val responder = NoiseXX(false, DhKey(v.str("responder_static").unhex()), DetRand("$name responder ephemeral"))
      val messages = v["messages"]!!.arr.map { it.obj }
      var initiatorSession: Pair<CipherState, CipherState>? = null
      var responderSession: Pair<CipherState, CipherState>? = null
      for ((i, m) in messages.withIndex()) {
        val (writer, reader) = if (i == 1) responder to initiator else initiator to responder
        val written = writer.write(m.str("payload").unhex())
        assertEquals("$name: message ${i + 1}", m.str("message"), written.message.hex())
        val read = reader.read(m.str("message").unhex())
        assertEquals("$name: payload ${i + 1}", m.str("payload"), read.payload.hex())
        if (i == 2) {
          initiatorSession = written.session
          responderSession = read.session
        }
      }
      assertEquals(name, v.str("handshake_hash"), initiator.handshakeHash.hex())
      assertEquals(name, v.str("handshake_hash"), responder.handshakeHash.hex())
      assertArrayEquals(DhKey(v.str("initiator_static").unhex()).public, responder.remoteStatic)
      val (iSend, iRecv) = initiatorSession!!
      val (rSend, rRecv) = responderSession!!
      for (t in v["transport"]!!.arr.map { it.obj }) {
        val fromInitiator = t.bool("from_initiator")
        val pattern = t.long("pattern_length").toInt()
        val plaintext = if (pattern > 0) ByteArray(pattern) { i -> (i * 13).toByte() } else t.str("plaintext").unhex()
        val sealed = (if (fromInitiator) iSend else rSend).encrypt(EMPTY, plaintext)
        if (pattern > 0) assertEquals(name, t.str("ciphertext_sha256"), sealed.sha256hex())
        else assertEquals(name, t.str("ciphertext"), sealed.hex())
        assertArrayEquals(plaintext, (if (fromInitiator) rRecv else iRecv).decrypt(EMPTY, sealed))
      }
    }
  }

  @Test
  fun framesMatchGo() {
    val frames = Fixtures.json("envelope.json").obj["frames"]!!.arr.map { it.obj }
    for (v in frames) {
      val f =
          Frame(
              stream = v.long("stream"),
              kind = FrameKind.entries.first { it.name.lowercase() == v.str("kind") },
              verb = v.str("verb"),
              path = v.str("path"),
              headers = (v["headers"]?.arr ?: emptyList()).map { Header(it.obj.str("key"), it.obj.str("value")) },
              status = v.long("status").toInt(),
              data = v.str("data").unhex(),
              endBody = v.bool("end_body"),
              code = v.long("code").toInt(),
              reason = v.str("reason"),
          )
      assertEquals(v.str("frame"), encodeFrame(f).hex())
      assertEquals(v.str("request"), encodeServiceRequest(f).hex())
      assertEquals(f, decodeFrame(v.str("frame").unhex()))
      if (f.kind == FrameKind.Request) {
        assertEquals("muse: the VM sent a request", runCatching { decodeServiceResponse(v.str("response").unhex()) }.exceptionOrNull()?.message)
      } else {
        assertEquals(f, decodeServiceResponse(v.str("response").unhex()))
      }
    }
  }

  @Test
  fun chunksMatchGo() {
    for (v in Fixtures.json("envelope.json").obj["chunks"]!!.arr.map { it.obj }) {
      val envelope = DetRand(v.str("seed")).bytes(v.long("length").toInt())
      assertEquals(v.str("envelope_sha256"), envelope.sha256hex())
      val pieces = chunk(envelope)
      assertEquals("one id for every piece", 1, pieces.map { splitChunkId(it).first.hex() }.toSet().size)
      assertEquals(v.strings("pieces_without_id_sha256"), pieces.map { splitChunkId(it).second.sha256hex() })
      assertEquals(
          v.strings("pieces_without_id_head"),
          pieces.map { splitChunkId(it).second.let { r -> r.copyOf(minOf(r.size, 24)) }.hex() })
      val a = Assembler()
      for ((i, p) in pieces.withIndex()) {
        val whole = a.add(p)
        if (i < pieces.size - 1) assertNull(whole) else assertArrayEquals(envelope, whole)
      }
    }
  }

  @Test
  fun controlStreamMatchesGo() {
    val v = Fixtures.json("envelope.json").obj["control"]!!.obj
    val stream = v.str("stream").unhex()
    val d = ControlDecoder()
    val got = mutableListOf<ControlMessage>()
    for (i in stream.indices step 5) got += d.feed(stream.copyOfRange(i, minOf(stream.size, i + 5)))
    val want = v["messages"]!!.arr.map { it.obj }
    assertEquals(want.size, got.size)
    for ((m, w) in got.zip(want)) {
      for ((key, raw) in w) {
        val field =
            when (key) {
              "id" -> m.id
              "method" -> m.method
              "event" -> m.event
              "command" -> m.command
              "params" -> m.params
              else -> error(key)
            }
        assertEquals(key, stateJson.parseToJsonElement(raw.jsonPrimitive.content), field)
      }
    }
    assertEquals(JsonPrimitive("link.unpaired"), got.last().event)
  }

  private fun splitChunkId(p: ByteArray): Pair<ByteArray, ByteArray> {
    if (p.isEmpty() || p[0] != 0x08.toByte()) return EMPTY to p
    var n = 1
    while (p[n].toInt() and 0x80 != 0) n++
    return p.copyOf(n + 1) to p.copyOfRange(n + 1, p.size)
  }
}
