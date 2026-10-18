// This file is a modified port of linux/src/musegadget/pairing.py from Meta's Muse Gadget SDK,
// Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.

package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.RandomSource
import dev.wobsoriano.muse.hmacSha256
import dev.wobsoriano.muse.isValidUtf8
import dev.wobsoriano.muse.sha256
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.FixedPointCombMultiplier

/**
 * P-256 for the pairing handshake. The shared secret comes from the platform's ECDH. Making a public
 * key from a drawn scalar, and checking that the phone's point is on the curve, are BouncyCastle's
 * lightweight curve arithmetic, since the JCA offers neither.
 */
internal object P256 {
  private val curve = CustomNamedCurves.getByName("secp256r1")
  val order: BigInteger = curve.n

  /** The curve's parameters as the platform's own provider names them. */
  private val params: ECParameterSpec by lazy {
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec("secp256r1"))
    (gen.generateKeyPair().public as ECPublicKey).params
  }

  /** The uncompressed public point of [scalar]. */
  fun publicKey(scalar: ByteArray): ByteArray =
      FixedPointCombMultiplier().multiply(curve.g, BigInteger(1, scalar)).normalize().getEncoded(false)

  /** Whether [point] is an uncompressed point on the curve, and not the point at infinity. */
  fun onCurve(point: ByteArray): Boolean =
      point.size == P256_POINT_BYTES &&
          point[0] == 0x04.toByte() &&
          try {
            val p = curve.curve.decodePoint(point)
            !p.isInfinity && p.isValid
          } catch (_: IllegalArgumentException) {
            false
          }

  /** The x coordinate of [scalar] times [point], 32 bytes. */
  fun ecdh(scalar: ByteArray, point: ByteArray): ByteArray {
    val kf = KeyFactory.getInstance("EC")
    val private = kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params))
    val x = BigInteger(1, point.copyOfRange(1, 33))
    val y = BigInteger(1, point.copyOfRange(33, 65))
    val public = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(private)
    ka.doPhase(public, true)
    val secret = ka.generateSecret()
    if (secret.size >= 32) return secret.copyOfRange(secret.size - 32, secret.size)
    return ByteArray(32 - secret.size) + secret
  }
}

internal class KeyException(message: String) : Exception(message)

/**
 * Draws a P-256 private scalar from [rand]; one outside the curve's range is drawn again. The scalar
 * comes from the stream itself so the published vectors can fix it.
 */
internal fun generateKey(rand: RandomSource): ByteArray {
  repeat(8) {
    val scalar = ByteArray(32).also(rand::read)
    val s = BigInteger(1, scalar)
    if (s.signum() > 0 && s < P256.order) return scalar
  }
  throw KeyException("pairing: the random source gave no usable key")
}

internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray) = hmacSha256(salt, ikm)

/** HKDF-Expand for up to one block, which is all the pairing keys need. */
internal fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
  require(length <= 32)
  return hmacSha256(prk, info.toByteArray(Charsets.UTF_8), byteArrayOf(1)).copyOf(length)
}

internal fun gcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
  val c = Cipher.getInstance("AES/GCM/NoPadding")
  c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
  c.updateAAD(aad)
  return c.doFinal(plaintext)
}

internal fun gcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
  val c = Cipher.getInstance("AES/GCM/NoPadding")
  c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
  c.updateAAD(aad)
  return try {
    c.doFinal(sealed)
  } catch (_: GeneralSecurityException) {
    null
  }
}

/** Where the handshake stands. */
private enum class SessionState {
  Idle,
  WaitClientFinished,
  Ready,
  Provisioning,
}

// How long each stage may take before the keys are dropped.
internal const val CLIENT_FINISHED_TIMEOUT_MS = 60_000L
private const val CONFIRMED_TIMEOUT_MS = 120_000L
private const val PROVISIONING_TIMEOUT_MS = 120_000L

internal const val TO_DEVICE: Byte = 0
internal const val FROM_DEVICE: Byte = 1

/** The canonical v5 community transcript. Its SHA-256 is the transcript hash both sides mix into the keys. */
internal fun buildTranscript(dev: Device, mobilePub: String, devicePub: String, mobileNonce: String, deviceNonce: String): String =
    listOf(
            "hatch-link-pairing-v$PAIRING_VERSION",
            "version=$PAIRING_VERSION",
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=" + dev.deviceId,
            "node_id=" + dev.nodeId,
            "mac=" + dev.mac,
            "model=$PAIRING_MODEL",
            "firmware_version=" + dev.version,
            "selected_cipher_suite=$PAIRING_SUITE",
            "pairing_auth=$AUTH_COMMUNITY",
            "pairing_auth_epoch=0",
            "pairing_policy=$POLICY_APP",
            "confirm_timeout_seconds=0",
            "mobile_pub=$mobilePub",
            "device_pub=$devicePub",
            "mobile_nonce=$mobileNonce",
            "device_nonce=$deviceNonce",
        )
        .joinToString("\n")

/**
 * Named from the phone's side, as the protocol names them: the device opens what it receives with
 * [mobileTx] and seals what it sends with [mobileRx].
 */
internal class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

internal fun sessionSecret(ecdhSecret: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): ByteArray {
  val salt = sha256(mobileNonce, deviceNonce, transcriptHash)
  return hkdfExpand(hkdfExtract(salt, ecdhSecret), RECORD_LABEL, 32)
}

internal fun deriveSessionKeys(ecdhSecret: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): SessionKeys {
  val secret = sessionSecret(ecdhSecret, mobileNonce, deviceNonce, transcriptHash)
  val id = sha256(SESSION_ID_LABEL.toByteArray(), transcriptHash, ecdhSecret)
  return SessionKeys(hkdfExpand(secret, "mobile->device", 32), hkdfExpand(secret, "device->mobile", 32), id.copyOf(16))
}

internal fun recordNonce(d: Byte, counter: ULong): ByteArray {
  val nonce = ByteArray(12)
  nonce[0] = d
  for (i in 0 until 8) nonce[4 + i] = (counter shr (56 - 8 * i)).toByte()
  return nonce
}

internal fun recordAad(sessionId: String, d: Byte, counter: ULong): ByteArray =
    "$RECORD_LABEL|$sessionId|${if (d == FROM_DEVICE) "d2m" else "m2d"}|$counter".toByteArray()

/**
 * One device's pairing state: community mode only, so auth is "none", the policy is confirm_app, and a
 * valid client-finished record confirms the session without a button. It is not safe for concurrent
 * use; the Controller's loop owns it.
 *
 * A generation names one stretch of one session. Work that outlives a command (a Wi-Fi join, a token
 * check) keeps the generation it started under, so what it finds cannot act on a later session. Zero
 * is never a live generation.
 */
internal class PairingSession(
    private val dev: Device,
    private val sdkToken: String,
    private val rand: RandomSource,
    var now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  var gen = 1L
    private set

  private var state = SessionState.Idle
  private var deadline = 0L
  private var rx: ByteArray? = null
  private var tx: ByteArray? = null
  var id = ""
    private set

  private var rxCounter = 0uL
  private var txCounter = 0uL

  val idle
    get() = state == SessionState.Idle

  fun confirmed(): Boolean {
    expire()
    return state == SessionState.Ready || state == SessionState.Provisioning
  }

  private fun isCurrent(g: Long) = g != 0L && g == gen

  /** Ends the session and makes everything started under it stale. */
  fun reset() {
    gen++
    clear()
  }

  private fun clear() {
    state = SessionState.Idle
    deadline = 0
    rx = null
    tx = null
    id = ""
    rxCounter = 0uL
    txCounter = 0uL
  }

  /** Drops the keys of a session that ran out of time. The generation stays, so whoever owns the expired session still recognizes it. */
  private fun expire(): Boolean {
    if (state == SessionState.Idle || now() <= deadline) return false
    clear()
    return true
  }

  /** Starts a session and returns the pairing_ready to send. It throws only when the device could not make its half. */
  fun hello(h: ClientHello): PairingReady {
    reset()
    val key = generateKey(rand)
    val deviceNonce = ByteArray(NONCE_BYTES).also(rand::read)
    val devicePub = P256.publicKey(key)
    val transcript = buildTranscript(dev, b64url(h.pub), b64url(devicePub), b64url(h.nonce), b64url(deviceNonce))
    val transcriptHash = sha256(transcript.toByteArray())
    val secret = P256.ecdh(key, h.pub)
    val keys = deriveSessionKeys(secret, h.nonce, deviceNonce, transcriptHash)
    rx = keys.mobileTx
    tx = keys.mobileRx
    id = b64url(keys.sessionId)
    state = SessionState.WaitClientFinished
    deadline = now() + CLIENT_FINISHED_TIMEOUT_MS
    return PairingReady(dev.deviceId, dev.nodeId, dev.mac, dev.version, b64url(devicePub), b64url(deviceNonce), b64url(transcriptHash), id)
  }

  /**
   * Decrypts one record from the phone. Any failure (another session's record, a skipped or replayed
   * counter, a bad tag, a session out of time) ends the session, as the firmware does.
   */
  fun open(r: Record): ByteArray? {
    if (expire() || state == SessionState.Idle || r.sessionId != id || r.counter != rxCounter) {
      reset()
      return null
    }
    val plaintext = gcmOpen(checkNotNull(rx), recordNonce(TO_DEVICE, r.counter), r.ciphertext + r.tag, recordAad(id, TO_DEVICE, r.counter))
    if (plaintext == null || !isValidUtf8(plaintext)) {
      reset()
      return null
    }
    rxCounter++
    return plaintext
  }

  /**
   * Confirms the session if the first record was exactly the client-finished command. Under
   * confirm_app the app already collected consent, so nothing more is asked. It returns the new
   * generation, or zero after ending the session.
   */
  fun clientFinished(exact: Boolean): Long {
    if (!exact || expire() || state != SessionState.WaitClientFinished || rxCounter != 1uL) {
      reset()
      return 0
    }
    gen++
    state = SessionState.Ready
    deadline = now() + CONFIRMED_TIMEOUT_MS
    return gen
  }

  /**
   * Moves a confirmed session into provisioning and returns its generation. A session already
   * provisioning keeps its generation, so the phone may retry after a failed Wi-Fi join.
   */
  fun markProvisioning(): Long {
    if (!expire() && state == SessionState.Ready) {
      gen++
      state = SessionState.Provisioning
      deadline = now() + PROVISIONING_TIMEOUT_MS
    }
    return if (state == SessionState.Provisioning) gen else 0
  }

  /** Whether [g] is the provisioning still under way. */
  fun provisioningValid(g: Long) = isCurrent(g) && !expire() && state == SessionState.Provisioning

  fun extendProvisioning(g: Long): Boolean {
    if (!provisioningValid(g)) return false
    deadline = now() + PROVISIONING_TIMEOUT_MS
    return true
  }

  /** Encrypts one record for the phone, or returns null when there is no session, or when [g] is set and is no longer current. */
  fun seal(plaintext: ByteArray, g: Long): Envelope? {
    if ((g != 0L && g != gen) || expire() || state == SessionState.Idle) return null
    val counter = txCounter
    val sealed = gcmSeal(checkNotNull(tx), recordNonce(FROM_DEVICE, counter), plaintext, recordAad(id, FROM_DEVICE, counter))
    txCounter++
    return Envelope(id, counter.toString(), b64url(sealed.copyOfRange(0, sealed.size - TAG_BYTES)), b64url(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)))
  }

  /** Seals a status message. pairing_confirmed also carries the SDK token; apps read only type and status, so older ones ignore it. */
  fun sealStatus(st: Status, g: Long): Envelope? =
      seal(statusMessage(st, if (st == Status.Confirmed) sdkToken else "").toString().toByteArray(), g)
}
