// This file is a modified port of linux/src/musegadget/pairing.py and ble_setup.py from Meta's Muse
// Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License,
// Version 2.0. It holds the wire shapes and the checks the SDK makes on what the phone sends.

package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.isValidUtf8
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Wire identifiers. "hatch" is the protocol's own name for a Muse gadget and stays only here.
internal const val PAIRING_VERSION = 5
internal const val PAIRING_MODEL = "hatch_link"
internal const val PAIRING_SUITE = "p256-hkdf-sha256-aes-gcm-v1"
internal const val AUTH_COMMUNITY = "none"
internal const val POLICY_APP = "confirm_app"
internal const val DEVICE_ID_PREFIX = "hatch-link:"
internal const val RECORD_LABEL = "hatch-link ble setup v1"
internal const val SESSION_ID_LABEL = "hatch-link session id v1"

internal const val ACTION_HELLO = "pairing_client_hello"
internal const val ACTION_ENCRYPTED = "pairing_encrypted"
internal const val ACTION_DEVICE_INFO = "get_device_info"
internal const val ACTION_CLIENT_FINISHED = "pairing_client_finished"
internal const val ACTION_WIFI_SCAN = "wifi_scan"
internal const val ACTION_PROVISION = "provision_v2"

/** Refused outside a confirmed, encrypted session, including the ones this device does not carry out. */
internal val sensitiveActions = setOf("provision", ACTION_PROVISION, ACTION_WIFI_SCAN, "ota", "device.ota", "unpair", "set_wifi", "set_auth")

/** A word the phone is told, inside a status message or bare. */
internal enum class Status(val wire: String) {
  Confirmed("pairing_confirmed"),
  WiFiConnecting("wifi_connecting"),
  WiFiConnected("wifi_connected"),
  WiFiFailed("wifi_failed"),
  AuthOk("auth_ok"),
  AuthFailed("auth_failed"),
  Storage("error_storage"),
  InvalidCommand("error_invalid_command"),
  UnknownAction("error_unknown_action"),
  EncryptionRequired("error_encryption_required"),
  ConfirmRequired("error_pairing_confirm_required"),
  InvalidHello("error_pairing_invalid_hello"),
  Unavailable("error_pairing_unavailable"),
  Decrypt("error_pairing_decrypt"),
  MissingCredentials("error_missing_credentials"),
  InProgress("error_operation_in_progress"),
}

/** May go out bare, before any session exists. Everything else is only ever sent encrypted. */
internal val plaintextStatuses = setOf(Status.EncryptionRequired, Status.InvalidHello, Status.Unavailable, Status.Decrypt)

internal const val P256_POINT_BYTES = 65
internal const val NONCE_BYTES = 16
internal const val TAG_BYTES = 16
private const val MAX_B64_CHARS = 4096
internal const val MAX_CIPHERTEXT_B64 = 16384

// What the device sends. Each is written field for field in the order the Go structs gave them, so a
// sealed record's bytes are the ones musecore would have sealed.

internal fun deviceInfo(d: Device, networkReady: Boolean): JsonObject = buildJsonObject {
  put("type", "device_info")
  put("node_id", d.nodeId)
  put("version", d.version)
  put("device_id", d.deviceId)
  put("mac", d.mac)
  put("model", PAIRING_MODEL)
  put("pairing_protocol", PAIRING_VERSION)
  put("pairing_auth", AUTH_COMMUNITY)
  put("pairing_auth_epoch", 0)
  put("pairing_policy", POLICY_APP)
  put("build_sha", "")
  put("network_ready", networkReady)
}

internal data class PairingReady(
    val deviceId: String,
    val nodeId: String,
    val mac: String,
    val firmwareVersion: String,
    val devicePub: String,
    val deviceNonce: String,
    val transcriptHash: String,
    val sessionId: String,
) {
  fun toJson(): JsonObject = buildJsonObject {
    put("type", "pairing_ready")
    put("version", PAIRING_VERSION)
    put("device_id", deviceId)
    put("node_id", nodeId)
    put("mac", mac)
    put("model", PAIRING_MODEL)
    put("firmware_version", firmwareVersion)
    put("pairing_auth", AUTH_COMMUNITY)
    put("pairing_auth_epoch", 0)
    put("pairing_policy", POLICY_APP)
    put("device_pub", devicePub)
    put("device_nonce", deviceNonce)
    put("transcript_hash", transcriptHash)
    put("session_id", sessionId)
  }
}

internal fun statusMessage(st: Status, sdkToken: String): JsonObject = buildJsonObject {
  put("type", "status")
  put("status", st.wire)
  if (sdkToken.isNotEmpty()) put("sdk_token", sdkToken)
}

internal data class Envelope(val sessionId: String, val counter: String, val ciphertext: String, val tag: String) {
  fun toJson(): JsonObject = buildJsonObject {
    put("type", ACTION_ENCRYPTED)
    put("session_id", sessionId)
    put("counter", counter)
    put("ciphertext", ciphertext)
    put("tag", tag)
  }
}

internal fun scanResult(networks: List<WiFiNetwork>): JsonObject = buildJsonObject {
  put("type", "wifi_scan_result")
  put(
      "networks",
      buildJsonArray {
        for (n in networks) {
          add(buildJsonObject {
            put("ssid", n.ssid)
            put("rssi", n.rssi)
            put("secure", n.secure)
          })
        }
      })
}

// What the phone sends, once checked.

/** A pairing_client_hello whose offer this device accepts and whose key is on the curve. */
internal class ClientHello(val pub: ByteArray, val nonce: ByteArray)

/** A pairing_encrypted envelope that is well formed; whether it opens is the session's call. */
internal class Record(val sessionId: String, val counter: ULong, val ciphertext: ByteArray, val tag: ByteArray)

/** A provision_v2 that carries device tokens. */
internal class Provision(val ssid: String, val password: String, val result: Result)

/** A hello for a version, auth or policy this device does not speak. A session already open survives it. */
internal class HelloOfferException : Exception("pairing: unsupported hello")

/** A hello with bad key material. It ends any session already open. */
internal class HelloKeyException : Exception("pairing: bad hello key material")

private val strict = Json

/** A command as it arrived: a JSON object, its values not yet looked at. Keys are matched exactly. */
internal class Fields(private val m: Map<String, JsonElement>) {
  val size
    get() = m.size

  /** The value at [key] when it is a JSON string. */
  fun str(key: String): String? = (m[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  fun text(key: String) = str(key).orEmpty()

  /** The value at [key] when it is a JSON number; true and "5" are not. */
  fun number(key: String): Double? {
    val v = m[key] as? JsonPrimitive ?: return null
    if (v.isString) return null
    val c = v.content
    if (c.isEmpty() || !(c[0] == '-' || c[0] in '0'..'9')) return null
    return c.toDoubleOrNull()
  }

  /**
   * The value at [key] decoded from unpadded base64url. Only the alphabet is taken, as the firmware
   * takes it: no padding, no line endings.
   */
  fun b64(key: String, maxChars: Int = MAX_B64_CHARS): ByteArray? {
    val s = str(key) ?: return null
    if (s.isEmpty() || s.length > maxChars || s.length % 4 == 1) return null
    if (s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
    return try {
      Base64.getUrlDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  companion object {
    fun parse(raw: ByteArray): Fields? {
      if (!isValidUtf8(raw)) return null
      return try {
        (strict.parseToJsonElement(String(raw, Charsets.UTF_8)) as? JsonObject)?.let(::Fields)
      } catch (_: Exception) {
        null
      }
    }
  }
}

internal fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

internal fun parseHello(f: Fields): ClientHello {
  val version = f.number("version")
  if (version == null || version != PAIRING_VERSION.toDouble() || f.text("pairing_auth") != AUTH_COMMUNITY || f.text("pairing_policy") != POLICY_APP) {
    throw HelloOfferException()
  }
  val pub = f.b64("mobile_pub")
  val nonce = f.b64("mobile_nonce")
  if (pub == null || nonce == null || pub.size != P256_POINT_BYTES || pub[0] != 0x04.toByte() || nonce.size != NONCE_BYTES) {
    throw HelloKeyException()
  }
  if (!P256.onCurve(pub)) throw HelloKeyException()
  return ClientHello(pub, nonce)
}

internal fun parseRecord(f: Fields): Record? {
  val sessionId = f.str("session_id") ?: return null
  val counterText = f.str("counter") ?: return null
  val ciphertext = f.b64("ciphertext", MAX_CIPHERTEXT_B64) ?: return null
  val tag = f.b64("tag") ?: return null
  if (tag.size != TAG_BYTES) return null
  // Digits only at base 10, and the range is the counter's.
  if (counterText.isEmpty() || counterText.any { it !in '0'..'9' }) return null
  val counter = counterText.toULongOrNull() ?: return null
  return Record(sessionId, counter, ciphertext, tag)
}

/** Whether the command is exactly {"action":"pairing_client_finished"}. */
internal fun parseClientFinished(f: Fields) = f.size == 1 && f.text("action") == ACTION_CLIENT_FINISHED

internal fun parseProvision(f: Fields): Provision? {
  val ssid = f.str("ssid")
  val password = f.str("password")
  val result =
      Result(
          accessToken = f.text("access_token"),
          refreshToken = f.text("refresh_token"),
          tokenType = f.text("token_type"),
          username = f.text("username"),
          apiUrl = httpsOnly(f.text("api_url")),
          apiUrlV2 = httpsOnly(f.text("api_url_v2")),
          noiseHost = f.text("noise_host"))
  if (ssid == null || password == null || result.accessToken.isEmpty() || result.refreshToken.isEmpty() || result.tokenType != "device") return null
  return Provision(ssid, password, result)
}

/** Drops a URL the tokens must not be sent to. */
private fun httpsOnly(u: String) = if (u.startsWith("https://")) u else ""
