package dev.wobsoriano.muse

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519

/** Fills its argument with random bytes. Tests make it reproducible. */
fun interface RandomSource {
  fun read(into: ByteArray)

  companion object {
    val Secure: RandomSource = SecureRandom().let { r -> RandomSource { r.nextBytes(it) } }
  }
}

class NoiseException(message: String) : MuseException(message)

/** A Curve25519 key pair. The private half is the 32 bytes drawn for it, clamped only when used. */
internal class DhKey(val private: ByteArray) {
  val public: ByteArray = ByteArray(X25519.POINT_SIZE).also { X25519.scalarMultBase(private, 0, it, 0) }

  fun dh(peer: ByteArray): ByteArray {
    val out = ByteArray(X25519.POINT_SIZE)
    if (!X25519.calculateAgreement(private, 0, peer, 0, out, 0)) throw NoiseException("noise: bad public key")
    return out
  }

  companion object {
    fun generate(rand: RandomSource) = DhKey(ByteArray(X25519.SCALAR_SIZE).also(rand::read))
  }
}

internal fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(key, "HmacSHA256"))
  for (d in data) mac.update(d)
  return mac.doFinal()
}

internal fun sha256(vararg data: ByteArray): ByteArray {
  val md = MessageDigest.getInstance("SHA-256")
  for (d in data) md.update(d)
  return md.digest()
}

/** One direction of a Noise session: AES-256-GCM with the nonce counting up from zero. */
internal class CipherState(private val key: ByteArray? = null) {
  private var n = 0L

  val hasKey
    get() = key != null

  private fun nonce(): ByteArray {
    if (n == -1L) throw NoiseException("noise: nonce exhausted")
    val iv = ByteArray(12)
    for (i in 0 until 8) iv[4 + i] = (n ushr (56 - 8 * i)).toByte()
    return iv
  }

  fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
    val k = key ?: return plaintext
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce()))
    cipher.updateAAD(ad)
    val out = cipher.doFinal(plaintext)
    n++
    return out
  }

  fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
    val k = key ?: return ciphertext
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce()))
    cipher.updateAAD(ad)
    val out =
        try {
          cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
          throw NoiseException("noise: authentication failed")
        }
    n++
    return out
  }
}

/**
 * Noise_XX_25519_AESGCM_SHA256 with an empty prologue, the handshake the Muse VM speaks. It is the
 * protocol's own state machine (noiseprotocol.org, revision 34), message by message: the initiator
 * writes the first and third, the responder the second.
 */
internal class NoiseXX(private val initiator: Boolean, private val s: DhKey, private val rand: RandomSource) {
  private var h: ByteArray
  private var ck: ByteArray
  private var cipher = CipherState()
  private var e: DhKey? = null
  private var re: ByteArray? = null
  private var rs: ByteArray? = null
  private var step = 0

  init {
    val name = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII)
    h = name.copyOf(32)
    ck = h
    mixHash(EMPTY)
  }

  /** The handshake hash, once the handshake is done: what both sides agree the session was. */
  val handshakeHash
    get() = h

  /** The remote party's static key, once the handshake has carried it. */
  val remoteStatic
    get() = rs

  private fun mixHash(data: ByteArray) {
    h = sha256(h, data)
  }

  private fun hkdf(ikm: ByteArray): Pair<ByteArray, ByteArray> {
    val temp = hmacSha256(ck, ikm)
    val out1 = hmacSha256(temp, byteArrayOf(1))
    val out2 = hmacSha256(temp, out1, byteArrayOf(2))
    return out1 to out2
  }

  private fun mixKey(ikm: ByteArray) {
    val (nextCk, key) = hkdf(ikm)
    ck = nextCk
    cipher = CipherState(key)
  }

  private fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encrypt(h, plaintext).also(::mixHash)

  private fun decryptAndHash(ciphertext: ByteArray): ByteArray = cipher.decrypt(h, ciphertext).also { mixHash(ciphertext) }

  private val keyLength
    get() = X25519.POINT_SIZE + if (cipher.hasKey) 16 else 0

  /** The sending half and then the receiving half of the session. */
  private fun split(): Pair<CipherState, CipherState> {
    val (k1, k2) = hkdf(EMPTY)
    val toResponder = CipherState(k1)
    val toInitiator = CipherState(k2)
    return if (initiator) toResponder to toInitiator else toInitiator to toResponder
  }

  private fun dh(local: DhKey?, remote: ByteArray?) = checkNotNull(local).dh(checkNotNull(remote))

  /** Writes the next message. After the last, the result also holds the session's two halves. */
  fun write(payload: ByteArray = EMPTY): Written {
    val out = java.io.ByteArrayOutputStream()
    when {
      step == 0 && initiator -> {
        val e = DhKey.generate(rand).also { this.e = it }
        out.write(e.public)
        mixHash(e.public)
      }
      step == 1 && !initiator -> {
        val e = DhKey.generate(rand).also { this.e = it }
        out.write(e.public)
        mixHash(e.public)
        mixKey(dh(e, re))
        out.write(encryptAndHash(s.public))
        mixKey(dh(s, re))
      }
      step == 2 && initiator -> {
        out.write(encryptAndHash(s.public))
        mixKey(dh(s, re))
      }
      else -> throw NoiseException("noise: not this side's turn to write")
    }
    out.write(encryptAndHash(payload))
    step++
    return Written(out.toByteArray(), if (step == 3) split() else null)
  }

  /** Reads the next message and returns its payload. After the last, the result also holds the session's two halves. */
  fun read(message: ByteArray): Read {
    var at = 0
    fun take(n: Int): ByteArray {
      if (message.size - at < n) throw NoiseException("noise: message is too short")
      return message.copyOfRange(at, at + n).also { at += n }
    }
    when {
      step == 0 && !initiator -> {
        re = take(X25519.POINT_SIZE).also(::mixHash)
      }
      step == 1 && initiator -> {
        re = take(X25519.POINT_SIZE).also(::mixHash)
        mixKey(dh(e, re))
        rs = decryptAndHash(take(keyLength))
        mixKey(dh(e, rs))
      }
      step == 2 && !initiator -> {
        rs = decryptAndHash(take(keyLength))
        mixKey(dh(e, rs))
      }
      else -> throw NoiseException("noise: not this side's turn to read")
    }
    val payload = decryptAndHash(message.copyOfRange(at, message.size))
    step++
    return Read(payload, if (step == 3) split() else null)
  }

  class Written(val message: ByteArray, val session: Pair<CipherState, CipherState>?)

  class Read(val payload: ByteArray, val session: Pair<CipherState, CipherState>?)
}
