package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.RandomSource
import dev.wobsoriano.muse.hex
import dev.wobsoriano.muse.sha256
import dev.wobsoriano.muse.unhex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Meta SDK's published vectors for pairing version 5 (src/test/resources/pairing). */
internal class Vector(private val v: JsonObject) {
  operator fun get(key: String) = v[key]!!.jsonPrimitive.content

  val name
    get() = this["name"]

  fun device() = Device(this["node_id"], "MuseGadget000001", this["mac"], this["firmware_version"])

  fun hello(): ClientHello =
      parseHello(
          checkNotNull(
              Fields.parse(
                  buildJsonObject {
                        put("action", ACTION_HELLO)
                        put("version", 5)
                        put("pairing_auth", "none")
                        put("pairing_policy", "confirm_app")
                        put("mobile_pub", this@Vector["mobile_pub"])
                        put("mobile_nonce", this@Vector["mobile_nonce"])
                      }
                      .toString()
                      .toByteArray())))

  /** A session whose key and nonce are the vector's. */
  fun session(): PairingSession = PairingSession(device(), "", fixed(this["device_private_scalar_hex"].unhex() + unb64(this["device_nonce"])))

  companion object {
    /** The one vector for what this device speaks: community auth with the confirm_app policy. */
    const val APP = "community_app_v5"

    fun load(): Map<String, Vector> {
      val text = checkNotNull(Vector::class.java.getResource("/pairing/link_pairing_v5.json")).readText()
      val all = Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray.map { Vector(it.jsonObject) }.associateBy { it.name }
      assertTrue("expected three vectors including $APP, got ${all.size}", all.size == 3 && APP in all)
      return all
    }
  }
}

fun unb64(s: String): ByteArray =
    checkNotNull(Fields(mapOf("v" to JsonPrimitive(s) as JsonElement)).b64("v", MAX_CIPHERTEXT_B64)) { "$s is not base64url" }

/** A random source that hands out exactly these bytes, as Go's bytes.Reader does, and then fails. */
fun fixed(bytes: ByteArray): RandomSource {
  var at = 0
  return RandomSource { into ->
    if (bytes.size - at < into.size) throw IllegalStateException("EOF")
    bytes.copyInto(into, 0, at, at + into.size)
    at += into.size
  }
}

class SessionTest {
  @Test
  fun vectorKeySchedule() {
    for ((name, v) in Vector.load()) {
      val mobile = v["mobile_private_scalar_hex"].unhex()
      val device = generateKey(fixed(v["device_private_scalar_hex"].unhex()))
      assertEquals(name, v["mobile_pub"], b64url(P256.publicKey(mobile)))
      assertEquals(name, v["device_pub"], b64url(P256.publicKey(device)))
      val secret = P256.ecdh(device, P256.publicKey(mobile))
      assertEquals(name, v["ecdh_secret_hex"], secret.hex())
      val hash = sha256(v["transcript"].toByteArray())
      assertEquals(name, v["transcript_hash"], b64url(hash))
      val mobileNonce = unb64(v["mobile_nonce"])
      val deviceNonce = unb64(v["device_nonce"])
      assertEquals(name, v["session_secret_hex"], sessionSecret(secret, mobileNonce, deviceNonce, hash).hex())
      val keys = deriveSessionKeys(secret, mobileNonce, deviceNonce, hash)
      assertEquals(name, v["mobile_tx_key_hex"], keys.mobileTx.hex())
      assertEquals(name, v["mobile_rx_key_hex"], keys.mobileRx.hex())
      assertEquals(name, v["session_id"], b64url(keys.sessionId))

      assertEquals(name, v["client_finished_aad"], String(recordAad(v["session_id"], TO_DEVICE, 0uL)))
      val sealed = gcmSeal(keys.mobileTx, recordNonce(TO_DEVICE, 0uL), v["client_finished_plaintext"].toByteArray(), recordAad(v["session_id"], TO_DEVICE, 0uL))
      assertEquals(name, v["client_finished_ciphertext"], b64url(sealed.copyOf(sealed.size - TAG_BYTES)))
      assertEquals(name, v["client_finished_tag"], b64url(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)))
    }
  }

  @Test
  fun vectorTranscript() {
    val v = Vector.load().getValue(Vector.APP)
    assertEquals(v["device_id"], v.device().deviceId)
    assertEquals(v["transcript"], buildTranscript(v.device(), v["mobile_pub"], v["device_pub"], v["mobile_nonce"], v["device_nonce"]))
  }

  @Test
  fun vectorSession() {
    val v = Vector.load().getValue(Vector.APP)
    val s = v.session()
    val ready = s.hello(v.hello())
    assertEquals(
        PairingReady(v["device_id"], v["node_id"], v["mac"], v["firmware_version"], v["device_pub"], v["device_nonce"], v["transcript_hash"], v["session_id"]),
        ready)
    assertEquals(
        """{"type":"pairing_ready","version":5,"device_id":"${v["device_id"]}","node_id":"${v["node_id"]}","mac":"${v["mac"]}","model":"hatch_link",""" +
            """"firmware_version":"${v["firmware_version"]}","pairing_auth":"none","pairing_auth_epoch":0,"pairing_policy":"confirm_app",""" +
            """"device_pub":"${v["device_pub"]}","device_nonce":"${v["device_nonce"]}","transcript_hash":"${v["transcript_hash"]}","session_id":"${v["session_id"]}"}""",
        ready.toJson().toString())

    val finished = Record(v["session_id"], 0uL, unb64(v["client_finished_ciphertext"]), unb64(v["client_finished_tag"]))
    val plaintext = s.open(finished)
    assertEquals(v["client_finished_plaintext"], plaintext?.let { String(it) })
    val gen = s.clientFinished(parseClientFinished(checkNotNull(Fields.parse(plaintext!!))))
    assertTrue("the vector's client-finished record did not confirm the session", gen != 0L && s.confirmed())

    // What the device seals, the phone's receive key opens.
    val env = checkNotNull(s.sealStatus(Status.Confirmed, gen))
    assertEquals("0", env.counter)
    assertEquals(v["session_id"], env.sessionId)
    val opened = gcmOpen(v["mobile_rx_key_hex"].unhex(), recordNonce(FROM_DEVICE, 0uL), unb64(env.ciphertext) + unb64(env.tag), "hatch-link ble setup v1|${v["session_id"]}|d2m|0".toByteArray())
    assertEquals("""{"type":"status","status":"pairing_confirmed"}""", opened?.let { String(it) })
  }

  @Test
  fun replayedRecordEndsTheSession() {
    val v = Vector.load().getValue(Vector.APP)
    val s = v.session()
    s.hello(v.hello())
    val finished = Record(v["session_id"], 0uL, unb64(v["client_finished_ciphertext"]), unb64(v["client_finished_tag"]))
    assertNotNull("first record did not open", s.open(finished))
    assertNull("a replayed record opened", s.open(finished))
    assertTrue("the session survived a replay", s.idle)
  }

  @Test
  fun sessionStagesExpire() {
    val v = Vector.load().getValue(Vector.APP)
    val s = v.session()
    var now = 1_000_000L
    s.now = { now }
    s.hello(v.hello())
    val gen = s.gen
    now += CLIENT_FINISHED_TIMEOUT_MS + 1000
    assertNull("a session past its deadline still sealed a record", s.sealStatus(Status.ConfirmRequired, 0))
    assertEquals("expiry changed the generation; its owner could no longer recognize it", gen, s.gen)
  }

  @Test
  fun helloIsCheckedAtTheBoundary() {
    val v = Vector.load().getValue(Vector.APP)
    val offPoint = b64url(byteArrayOf(0x04) + ByteArray(64))
    val nonce = v["mobile_nonce"]
    for ((name, tc) in
        mapOf<String, Pair<Pair<String, JsonElement>, String?>>(
            "version 4" to (("version" to JsonPrimitive(4)) to "offer"),
            "version true" to (("version" to JsonPrimitive(true)) to "offer"),
            "version as text" to (("version" to JsonPrimitive("5")) to "offer"),
            "button policy" to (("pairing_policy" to JsonPrimitive("confirm_press")) to "offer"),
            "fleet auth" to (("pairing_auth" to JsonPrimitive("fleet_ecdsa_p256_v1")) to "offer"),
            "version 5.0" to (("version" to JsonPrimitive(5.0)) to null),
            "point off curve" to (("mobile_pub" to JsonPrimitive(offPoint)) to "key"),
            "compressed point" to (("mobile_pub" to JsonPrimitive(b64url(byteArrayOf(0x02) + ByteArray(64)))) to "key"),
            "short nonce" to (("mobile_nonce" to JsonPrimitive("AAEC")) to "key"),
            "padded base64" to (("mobile_nonce" to JsonPrimitive("$nonce==")) to "key"),
            "base64 with spaces" to (("mobile_nonce" to JsonPrimitive(nonce.substring(0, 10) + "\n" + nonce.substring(10))) to "key"),
            "key not text" to (("mobile_pub" to JsonPrimitive(7)) to "key"),
        )) {
      val m =
          mutableMapOf<String, JsonElement>(
              "action" to JsonPrimitive(ACTION_HELLO),
              "version" to JsonPrimitive(5),
              "pairing_auth" to JsonPrimitive("none"),
              "pairing_policy" to JsonPrimitive("confirm_app"),
              "mobile_pub" to JsonPrimitive(v["mobile_pub"]),
              "mobile_nonce" to JsonPrimitive(nonce))
      m[tc.first.first] = tc.first.second
      val f = checkNotNull(Fields.parse(JsonObject(m).toString().toByteArray()))
      val got =
          try {
            parseHello(f)
            null
          } catch (e: HelloOfferException) {
            "offer"
          } catch (e: HelloKeyException) {
            "key"
          }
      assertEquals(name, tc.second, got)
    }
  }

  @Test
  fun everyStateCanFailExceptDone() {
    for (s in SetupState.entries) {
      val moves = transitions[s] ?: fail("$s has no row in transitions").let { emptyMap() }
      assertNotEquals("$s: windowClosed legal", s == SetupState.Done, Trigger.WindowClosed in moves)
    }
    assertTrue("done is not terminal", transitions.getValue(SetupState.Done).isEmpty())
  }
}
