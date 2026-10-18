package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.DetRand
import dev.wobsoriano.muse.Fixtures
import dev.wobsoriano.muse.hex
import dev.wobsoriano.muse.obj
import dev.wobsoriano.muse.str
import dev.wobsoriano.muse.strings
import dev.wobsoriano.muse.unhex
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whole pairing conversations that musecore's Controller held with a phone (the Go vectors in
 * src/test/resources/vectors/pairing.json; its README says where they came from),
 * replayed into the Kotlin Controller with the same random streams. Every notification has to come
 * out byte for byte as Go sent it: the framing, the handshake, the records and the JSON inside them.
 */
class PairingVectorTest {
  @Test
  fun conversationsMatchMusecore() {
    for (tr in Fixtures.json("pairing.json").jsonArray.map { it.obj }) {
      val mtu = tr.str("mtu").toInt()
      val d = tr["device"]!!.obj
      val dev = Device(d.str("NodeID"), d.str("BLEName"), d.str("MAC"), d.str("Version"))
      val sent = LinkedBlockingQueue<ByteArray>()
      val saved = CompletableDeferred<Result>()
      val c =
          Controller(
              SetupConfig(
                  device = dev,
                  transport =
                      object : Transport {
                        override fun send(packets: List<ByteArray>) = packets.forEach { sent.add(it) }

                        override fun mtu() = mtu

                        override fun disconnect() {}
                      },
                  save = { saved.complete(it) },
                  sdkToken = tr.str("sdk_token"),
                  network = AlreadyOnline(reachable = { true }, ssid = { tr.str("ssid") }),
                  verify = {},
                  rand = DetRand(tr.str("device_seed"))))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val done = CompletableDeferred<Throwable?>()
      scope.launch { done.complete(runCatching { c.run() }.exceptionOrNull()) }
      try {
        for (step in tr["steps"]!!.jsonArray.map { it.obj }) {
          val note = "mtu $mtu, ${step.str("note")}"
          for (w in step.strings("writes")) c.onWrite(w.unhex())
          val want = step.strings("notifications")
          val got = List(want.size) { sent.poll(3, TimeUnit.SECONDS)?.hex() ?: "nothing" }
          assertEquals(note, want, got)
        }
        assertEquals("mtu $mtu: run", null, runBlocking { withTimeout(3.seconds) { done.await() } })
        val s = tr["saved"]!!.obj
        assertEquals(
            Result(s.str("access_token"), s.str("refresh_token"), s.str("token_type"), s.str("username"), s.str("api_url"), s.str("api_url_v2"), s.str("noise_host")),
            runBlocking { saved.await() })
        assertEquals("mtu $mtu: said more than Go", null, sent.poll(100, TimeUnit.MILLISECONDS)?.hex())
      } finally {
        scope.cancel()
      }
    }
  }
}
