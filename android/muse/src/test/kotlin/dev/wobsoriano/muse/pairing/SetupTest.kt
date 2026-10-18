package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.assertFails
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val WAIT_SECONDS = 3L
private val b64 = Base64.getUrlEncoder().withoutPadding()
private val unb64 = Base64.getUrlDecoder()

private fun obj(vararg kv: Pair<String, Any?>): JsonObject =
    JsonObject(
        kv.associate { (k, v) ->
          k to
              when (v) {
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is JsonElement -> v
                else -> error(v)
              }
        })

/**
 * The Muse app's side, written from the protocol with the JCA alone. It shares no code with the
 * package, so the two agreeing means something.
 */
class Phone {
  private val key: KeyPair =
      KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
  private var tx: ByteArray? = null
  private var rx: ByteArray? = null
  var sessionId = ""
  var txCounter = 0L

  private val pub: ByteArray
    get() {
      val w = (key.public as ECPublicKey).w
      fun fixed(n: BigInteger) = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
      return byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
    }

  fun hello() =
      obj(
          "action" to "pairing_client_hello",
          "version" to 5,
          "pairing_auth" to "none",
          "pairing_policy" to "confirm_app",
          "mobile_pub" to b64.encodeToString(pub),
          "mobile_nonce" to b64.encodeToString(nonce))

  private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    data.forEach(mac::update)
    return mac.doFinal()
  }

  fun accept(ready: JsonObject) {
    fun text(k: String) = (ready[k] as? JsonPrimitive)?.content.orEmpty()
    assertEquals("expected pairing_ready, got $ready", "pairing_ready", text("type"))
    val transcript =
        listOf(
                "hatch-link-pairing-v5", "version=5", "initiator_role=mobile", "responder_role=link",
                "device_id=" + text("device_id"), "node_id=" + text("node_id"), "mac=" + text("mac"),
                "model=hatch_link", "firmware_version=" + text("firmware_version"),
                "selected_cipher_suite=p256-hkdf-sha256-aes-gcm-v1", "pairing_auth=none",
                "pairing_auth_epoch=0", "pairing_policy=confirm_app", "confirm_timeout_seconds=0",
                "mobile_pub=" + b64.encodeToString(pub), "device_pub=" + text("device_pub"),
                "mobile_nonce=" + b64.encodeToString(nonce), "device_nonce=" + text("device_nonce"))
            .joinToString("\n")
    val hash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
    assertEquals("the device hashed a different transcript", b64.encodeToString(hash), text("transcript_hash"))
    val devicePub = unb64.decode(text("device_pub"))
    val deviceNonce = unb64.decode(text("device_nonce"))
    val params = (key.public as ECPublicKey).params
    val peer =
        KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, devicePub.copyOfRange(1, 33)), BigInteger(1, devicePub.copyOfRange(33, 65))), params))
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(key.private)
    ka.doPhase(peer, true)
    val secret = ka.generateSecret()
    val salt = MessageDigest.getInstance("SHA-256").digest(nonce + deviceNonce + hash)
    val session = hmac(hmac(salt, secret), "hatch-link ble setup v1".toByteArray(), byteArrayOf(1))
    tx = hmac(session, "mobile->device".toByteArray(), byteArrayOf(1))
    rx = hmac(session, "device->mobile".toByteArray(), byteArrayOf(1))
    sessionId = text("session_id")
    txCounter = 0
  }

  private fun nonceFor(direction: Int, counter: Long) = ByteArray(12).also {
    it[0] = direction.toByte()
    for (i in 0 until 8) it[4 + i] = (counter ushr (56 - 8 * i)).toByte()
  }

  fun seal(command: JsonObject): JsonObject {
    val counter = txCounter++
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(tx, "AES"), GCMParameterSpec(128, nonceFor(0, counter)))
    c.updateAAD("hatch-link ble setup v1|$sessionId|m2d|$counter".toByteArray())
    val sealed = c.doFinal(command.toString().toByteArray())
    return obj(
        "action" to "pairing_encrypted",
        "session_id" to sessionId,
        "counter" to counter.toString(),
        "ciphertext" to b64.encodeToString(sealed.copyOf(sealed.size - 16)),
        "tag" to b64.encodeToString(sealed.copyOfRange(sealed.size - 16, sealed.size)))
  }

  fun open(envelope: JsonObject): JsonObject {
    assertTrue(
        "expected a record of session $sessionId, got $envelope",
        envelope["type"]?.jsonPrimitive?.content == "pairing_encrypted" && envelope["session_id"]?.jsonPrimitive?.content == sessionId)
    val counter = envelope["counter"]!!.jsonPrimitive.content.toLong()
    val sealed = unb64.decode(envelope["ciphertext"]!!.jsonPrimitive.content) + unb64.decode(envelope["tag"]!!.jsonPrimitive.content)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(rx, "AES"), GCMParameterSpec(128, nonceFor(1, counter)))
    c.updateAAD("hatch-link ble setup v1|$sessionId|d2m|$counter".toByteArray())
    return Json.parseToJsonElement(String(c.doFinal(sealed))).jsonObject
  }
}

/** The Transport: it reassembles what the device notifies and counts the hang-ups. */
class Link(val mtu: Int) : Transport {
  private val asm = Assembler()
  val messages = LinkedBlockingQueue<ByteArray>()
  val disconnects = LinkedBlockingQueue<Unit>()
  val errors = LinkedBlockingQueue<String>()

  override fun mtu() = mtu

  @Synchronized
  override fun send(packets: List<ByteArray>) {
    for (p in packets) {
      // Bare statuses go out unframed, whatever the MTU, as in the SDK.
      if (p[0] == 0xFE.toByte() && p.size > maxOf(mtu - 3, 20)) errors.add("packet of ${p.size} bytes at MTU $mtu")
      if (p.size > MAX_PACKET) errors.add("packet of ${p.size} bytes, over MAX_PACKET")
      asm.feed(p)?.let { messages.add(it.copyOf()) }
    }
  }

  override fun disconnect() {
    disconnects.add(Unit)
  }
}

val device = Device(nodeId = "homelink-0a1b2c", bleName = "MuseGadget0A1B2C", mac = "02:11:22:0a:1b:2c", version = "0.9.0")

const val SDK_TOKEN = "mgst_test"

val wantResult =
    Result(
        accessToken = "device-access",
        refreshToken = "device-refresh",
        tokenType = "device",
        username = "someone",
        apiUrl = "https://legacy-api.example",
        apiUrlV2 = "https://api.example",
        noiseHost = "noise.example")

fun provisionCommand(): MutableMap<String, JsonElement> =
    obj(
            "action" to "provision_v2", "ssid" to "HomeNet", "password" to "pw",
            "access_token" to "device-access", "refresh_token" to "device-refresh", "token_type" to "device",
            "username" to "someone", "api_url" to "https://legacy-api.example", "api_url_v2" to "https://api.example",
            "noise_host" to "noise.example")
        .toMutableMap()

class SetupHarness(
    mtu: Int = 185,
    val online: AtomicBoolean = AtomicBoolean(true),
    verify: (suspend (Result) -> Unit)? = null,
    save: (suspend (Result) -> Unit)? = null,
    window: Duration = Duration.ZERO,
) {
  val link = Link(mtu)
  var phone = Phone()
  val saved = LinkedBlockingQueue<Result>()
  /** What run ended with: null for success, or what it threw. */
  val done = CompletableDeferred<Throwable?>()
  private val progress = mutableListOf<Progress>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val c =
      Controller(
          SetupConfig(
              device = device,
              transport = link,
              sdkToken = SDK_TOKEN,
              network = AlreadyOnline(reachable = { online.get() }, ssid = { "HomeNet" }),
              verify = verify ?: {},
              save = save ?: { saved.add(it); Unit },
              progress = { p -> synchronized(progress) { progress.add(p) } },
              window = window))
  val job: Job = scope.launch { done.complete(runCatching { c.run() }.exceptionOrNull()) }

  fun close() {
    job.cancel()
    runBlocking { withTimeout(WAIT_SECONDS * 1000) { job.join() } }
    scope.cancel()
    assertTrue("link errors: ${link.errors}", link.errors.isEmpty())
  }

  /** Sends one command the way the app does: chunked to the MTU, one GATT write per chunk. */
  fun write(command: Map<String, JsonElement>) {
    for (p in encodeChunks(JsonObject(command).toString().toByteArray(), link.mtu)) c.onWrite(p)
  }

  fun next(): ByteArray = link.messages.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: error("the device sent nothing; progress so far ${states()}")

  fun nextJson(): JsonObject {
    val raw = next()
    return runCatching { Json.parseToJsonElement(String(raw)).jsonObject }.getOrElse { error("expected JSON, got ${String(raw)}") }
  }

  fun nextOpened() = phone.open(nextJson())

  fun nextStatus(): String {
    val m = nextOpened()
    assertEquals("expected a status, got $m", "status", m["type"]?.jsonPrimitive?.content)
    return m["status"]!!.jsonPrimitive.content
  }

  fun quiet() {
    link.messages.poll(100, TimeUnit.MILLISECONDS)?.let { fail("expected silence, the device sent ${String(it)}") }
  }

  fun states() = synchronized(progress) { progress.map { it.state } }

  fun last() = synchronized(progress) { progress.last() }

  fun waitFor(want: SetupState): Progress {
    val deadline = System.nanoTime() + WAIT_SECONDS * 1_000_000_000
    while (true) {
      val p = last()
      if (p.state == want) return p
      if (System.nanoTime() > deadline) fail("never reached $want; progress ${states()}")
      Thread.sleep(2)
    }
  }

  fun handshake() {
    write(phone.hello())
    phone.accept(nextJson())
    write(phone.seal(obj("action" to "pairing_client_finished")))
    assertEquals("after client finished", obj("type" to "status", "status" to "pairing_confirmed", "sdk_token" to SDK_TOKEN), nextOpened())
  }

  fun expectStatuses(vararg want: String) {
    for (w in want) assertEquals(w, nextStatus())
  }

  /** Waits for run to end, and checks how: null is success. */
  fun expectRun(want: Class<out Throwable>?): Throwable? {
    val err = runBlocking { withTimeout(WAIT_SECONDS * 1000) { done.await() } }
    if (want == null) assertNull("Run threw $err", err) else assertTrue("Run threw $err, want $want", want.isInstance(err))
    return err
  }

  fun disconnected(): Boolean = link.disconnects.poll(WAIT_SECONDS, TimeUnit.SECONDS) != null
}

class SetupTest {
  private val harnesses = mutableListOf<SetupHarness>()

  private fun start(
      mtu: Int = 185,
      online: AtomicBoolean = AtomicBoolean(true),
      verify: (suspend (Result) -> Unit)? = null,
      save: (suspend (Result) -> Unit)? = null,
      window: Duration = Duration.ZERO,
  ) = SetupHarness(mtu, online, verify, save, window).also { harnesses += it }

  @After
  fun stop() {
    harnesses.forEach(SetupHarness::close)
  }

  @Test
  fun wholeConversation() {
    for (mtu in listOf(23, 185, 517)) {
      val verified = AtomicReference<Result>()
      val h = start(mtu = mtu, verify = { verified.set(it) })

      h.write(obj("action" to "get_device_info"))
      val wantInfo =
          Json.parseToJsonElement(
              """{"type":"device_info","node_id":"${device.nodeId}","version":"${device.version}","device_id":"hatch-link:${device.mac}","mac":"${device.mac}",""" +
                  """"model":"hatch_link","pairing_protocol":5,"pairing_auth":"none","pairing_auth_epoch":0,"pairing_policy":"confirm_app","build_sha":"","network_ready":true}""")
      assertEquals("mtu $mtu: device_info", wantInfo, h.nextJson())

      h.write(obj("action" to "wifi_scan"))
      assertEquals("plaintext wifi_scan", "error_encryption_required", String(h.next()))

      h.handshake()

      h.write(h.phone.seal(obj("action" to "wifi_scan")))
      assertEquals(
          Json.parseToJsonElement("""{"type":"wifi_scan_result","networks":[{"ssid":"HomeNet","rssi":-40,"secure":false}]}"""), h.nextOpened())

      h.write(h.phone.seal(JsonObject(provisionCommand())))
      h.expectStatuses("wifi_connecting", "wifi_connected", "auth_ok")
      h.expectRun(null)

      assertEquals(wantResult, h.saved.poll())
      assertEquals(wantResult, verified.get())
      assertEquals(
          listOf(SetupState.Waiting, SetupState.Connected, SetupState.Confirmed, SetupState.Provisioning, SetupState.Done), h.states())
      assertNull("the phone was dropped after a clean setup", h.link.disconnects.poll())
    }
  }

  @Test
  fun deviceInfoAgainMidHandshake() {
    val h = start()
    h.write(h.phone.hello())
    h.phone.accept(h.nextJson())

    h.write(obj("action" to "get_device_info"))
    val got = h.nextJson()
    assertTrue("mid-handshake get_device_info answered $got", got["type"]?.jsonPrimitive?.content == "device_info" && got["model"]?.jsonPrimitive?.content == "hatch_link")
    // Any other plaintext is ignored once a handshake has begun.
    h.write(obj("action" to "wifi_scan"))
    h.quiet()

    h.write(h.phone.seal(obj("action" to "pairing_client_finished")))
    assertEquals("pairing_confirmed", h.nextStatus())
  }

  @Test
  fun handshakeRestartsOnTheSameConnection() {
    val h = start()
    h.handshake()

    h.write(obj("action" to "get_device_info"))
    assertEquals("device_info", h.nextJson()["type"]?.jsonPrimitive?.content)
    h.phone = Phone()
    h.handshake()

    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected", "auth_ok")
    h.expectRun(null)
    assertEquals(
        listOf(
            SetupState.Waiting, SetupState.Connected, SetupState.Confirmed, SetupState.Connected,
            SetupState.Confirmed, SetupState.Provisioning, SetupState.Done),
        h.states())
  }

  @Test
  fun disconnectMidway() {
    val h = start()
    h.handshake()
    h.c.onDisconnect()
    h.waitFor(SetupState.Waiting)

    // The session went with the phone: its records no longer open, and plaintext is answered again.
    h.write(obj("action" to "wifi_scan"))
    assertEquals("error_encryption_required", String(h.next()))
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    assertEquals("error_pairing_decrypt", String(h.next()))

    h.phone = Phone()
    h.handshake()
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected", "auth_ok")
    h.expectRun(null)
  }

  @Test
  fun disconnectInTheMiddleOfAChunkedWrite() {
    val h = start(mtu = 23)
    val packets = encodeChunks(h.phone.hello().toString().toByteArray(), 23)
    for (p in packets.dropLast(1)) h.c.onWrite(p)
    h.c.onDisconnect()
    h.c.onWrite(packets.last())
    h.quiet()
    h.handshake()
  }

  @Test
  fun tamperedCiphertext() {
    val h = start()
    h.write(h.phone.hello())
    h.phone.accept(h.nextJson())

    val finished = h.phone.seal(obj("action" to "pairing_client_finished")).toMutableMap()
    val ciphertext = unb64.decode(finished["ciphertext"]!!.jsonPrimitive.content)
    ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
    finished["ciphertext"] = JsonPrimitive(b64.encodeToString(ciphertext))
    h.write(finished)

    // The session is gone and plaintext is blocked, so the phone hears nothing and is dropped.
    assertTrue("the phone was not dropped after a tampered record", h.disconnected())
    h.link.messages.poll()?.let { fail("the device answered a tampered record with ${String(it)}") }
    assertEquals(Reason.Handshake, h.waitFor(SetupState.Failed).reason)

    // The untampered record is no good either: the keys went with the session.
    h.phone.txCounter = 0
    h.write(h.phone.seal(obj("action" to "pairing_client_finished")))
    h.quiet()

    h.c.onDisconnect()
    h.phone = Phone()
    h.handshake()
  }

  @Test
  fun scheduledHangUpSparesTheNextPhone() {
    val h = start()
    h.write(h.phone.hello())
    h.phone.accept(h.nextJson())
    val finished = h.phone.seal(obj("action" to "pairing_client_finished")).toMutableMap()
    finished["tag"] = JsonPrimitive(b64.encodeToString(ByteArray(16)))
    h.write(finished)
    h.waitFor(SetupState.Failed)

    // The phone leaves by itself and another connects before the hang-up comes due.
    h.c.onDisconnect()
    h.phone = Phone()
    h.handshake()
    assertNull("a hang-up meant for the first phone dropped the second", h.link.disconnects.poll(600, TimeUnit.MILLISECONDS))
  }

  @Test
  fun wrongFirstRecordNeverConfirms() {
    val h = start()
    h.write(h.phone.hello())
    h.phone.accept(h.nextJson())
    h.write(h.phone.seal(obj("action" to "wifi_scan")))
    assertEquals("error_pairing_confirm_required", h.nextStatus())
    h.write(h.phone.seal(obj("action" to "pairing_client_finished")))
    assertTrue("a late client-finished did not end the session", h.disconnected())
  }

  @Test
  fun saveFailureIsReportedAsStorageError() {
    val h = start(save = { throw java.io.IOException("disk full") })
    h.handshake()
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected", "error_storage")
    assertTrue("the phone was not dropped after the save failed", h.disconnected())
    val p = h.waitFor(SetupState.Failed)
    assertTrue("failed with ${p.reason}, ${p.error}", p.reason == Reason.Storage && p.error?.message == "disk full")
    assertFalse("Run returned; the window should stay open for another try", h.done.isCompleted)
  }

  @Test
  fun rejectedTokenIsNotSaved() {
    val h = start(verify = { throw Exception("HTTP 401") })
    h.handshake()
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected", "auth_failed")
    assertEquals(Reason.AuthRejected, h.waitFor(SetupState.Failed).reason)
    assertTrue("the phone was not dropped after the token was rejected", h.disconnected())
    h.saved.poll()?.let { fail("a rejected token was saved: $it") }
  }

  @Test
  fun offlineDeviceOffersNothingAndCanRetry() {
    val online = AtomicBoolean(false)
    val h = start(online = online)
    h.write(obj("action" to "get_device_info"))
    assertEquals(JsonPrimitive(false), h.nextJson()["network_ready"])
    h.handshake()
    h.write(h.phone.seal(obj("action" to "wifi_scan")))
    assertEquals(JsonArray(emptyList()), h.nextOpened()["networks"])
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_failed")
    assertEquals(Reason.Offline, h.waitFor(SetupState.Failed).reason)

    online.set(true)
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected", "auth_ok")
    h.expectRun(null)
  }

  @Test
  fun provisionNeedsDeviceTokens() {
    for ((name, change) in
        mapOf(
            "no refresh token" to ("refresh_token" to JsonPrimitive("")),
            "user token" to ("token_type" to JsonPrimitive("user")),
            "null access token" to ("access_token" to JsonNull),
            "null password" to ("password" to JsonNull),
        )) {
      val h = start()
      h.handshake()
      val command = provisionCommand()
      command[change.first] = change.second
      h.write(h.phone.seal(JsonObject(command)))
      assertEquals(name, "error_missing_credentials", h.nextStatus())
    }
  }

  @Test
  fun tokensGoOnlyToHttps() {
    val h = start()
    h.handshake()
    val command = provisionCommand()
    command["api_url"] = JsonPrimitive("http://legacy-api.example")
    command["api_url_v2"] = JsonPrimitive("ftp://api.example")
    h.write(h.phone.seal(JsonObject(command)))
    h.expectStatuses("wifi_connecting", "wifi_connected", "auth_ok")
    val got = h.saved.poll(WAIT_SECONDS, TimeUnit.SECONDS)!!
    assertTrue("saved $got with root ${got.apiRoot}", got.apiUrl == "" && got.apiUrlV2 == "" && got.apiRoot == "https://api.muse.ai")
  }

  @Test
  fun secondProvisionWhileOneIsRunning() {
    val release = CountDownLatch(1)
    val h = start(verify = { runInterruptible { release.await() } })
    h.handshake()
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected")
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("error_operation_in_progress")
    release.countDown()
    h.expectStatuses("auth_ok")
  }

  @Test
  fun tokenCheckForALostSessionSavesNothing() {
    val release = CountDownLatch(1)
    val h = start(verify = { runInterruptible { release.await() } })
    h.handshake()
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    h.expectStatuses("wifi_connecting", "wifi_connected")
    h.c.onDisconnect()
    h.waitFor(SetupState.Waiting)
    release.countDown()
    h.quiet()
    h.saved.poll()?.let { fail("tokens from a phone that left were saved: $it") }
  }

  @Test
  fun badCommands() {
    val h = start()
    h.write(h.phone.hello())
    h.phone.accept(h.nextJson())
    h.write(h.phone.seal(obj("action" to "pairing_client_finished")))
    h.nextStatus()

    h.write(h.phone.seal(obj("action" to "dance")))
    h.expectStatuses("error_unknown_action")
    h.write(h.phone.seal(obj("action" to "ota")))
    h.expectStatuses("error_unknown_action")
    h.c.onWrite("not json".toByteArray())
    h.expectStatuses("error_invalid_command")
    h.c.onWrite("[1,2]".toByteArray())
    h.expectStatuses("error_invalid_command")
  }

  @Test
  fun badHelloGetsAPlaintextError() {
    val h = start()
    val hello = h.phone.hello().toMutableMap()
    hello["pairing_policy"] = JsonPrimitive("confirm_press")
    h.write(hello)
    assertEquals("a hello for the button policy", "error_pairing_invalid_hello", String(h.next()))
  }

  @Test
  fun windowCloses() {
    val h = start(window = 50.milliseconds)
    h.write(h.phone.hello())
    h.phone.accept(h.nextJson())
    h.expectRun(WindowClosedException::class.java)
    val p = h.last()
    assertTrue("ended in ${p.state} for ${p.reason}", p.state == SetupState.Failed && p.reason == Reason.WindowClosed)
    // A transport that has not heard yet must not block.
    h.c.onWrite("""{"action":"get_device_info"}""".toByteArray())
    h.c.onDisconnect()
  }

  @Test
  fun cancelEndsRunWhileATokenCheckIsOut() {
    val entered = CountDownLatch(1)
    val h =
        start(
            verify = {
              entered.countDown()
              awaitCancellation()
            })
    h.handshake()
    h.write(h.phone.seal(JsonObject(provisionCommand())))
    assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
    h.job.cancel()
    runBlocking { withTimeout(WAIT_SECONDS * 1000) { h.job.join() } }
    val p = h.last()
    assertTrue("ended in ${p.state} for ${p.reason}", p.state == SetupState.Failed && p.reason == Reason.Canceled)
    assertTrue("run ended with ${h.done.isCompleted}", !h.done.isCompleted || h.done.getCompleted() is CancellationException)
  }

  @Test
  fun newChecksTheNames() {
    val ok = SetupConfig(device = device, transport = Link(23), save = {})
    Controller(ok)
    for ((name, change) in
        mapOf<String, (SetupConfig) -> SetupConfig>(
            "hyphen in BLE name" to { c -> SetupConfig(c.device.copy(bleName = "MuseGadget-0A1B2C"), c.transport, c.save) },
            "lower-case BLE name" to { c -> SetupConfig(c.device.copy(bleName = "MuseGadget0a1b2c"), c.transport, c.save) },
            "other node prefix" to { c -> SetupConfig(c.device.copy(nodeId = "muse-0a1b2c"), c.transport, c.save) },
            "upper-case MAC" to { c -> SetupConfig(c.device.copy(mac = "02:11:22:0A:1B:2C"), c.transport, c.save) },
        )) {
      assertFails("$name: New accepted it") { Controller(change(ok)) }
    }
  }

  @Test
  fun resultDoesNotPrintItsTokens() {
    val s = wantResult.toString()
    assertFalse("printed a token: $s", s.contains("device-access") || s.contains("device-refresh"))
  }
}
