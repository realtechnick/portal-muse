// Identity, Credentials and State are ported from linux/src/musegadget/identity.py and config.py in
// Meta's Muse Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the
// Apache License, Version 2.0 (LICENSE in this directory). Modified: rewritten in Kotlin.

/**
 * Makes the device a Muse gadget: it keeps one encrypted session open to the owner's Muse, runs the
 * commands Muse sends, and asks Muse things in text or as a voice note.
 *
 * It is a port of the Linux client in Meta's Muse Gadget SDK and speaks the same protocol. The device
 * token buys a list of VMs, each with its own bearer. A WebSocket to the VM carries a Noise XX
 * session, and inside that session ride HTTP-shaped streams: one long POST /link-control for
 * commands, one POST /chat/subscribe for everything Muse says, and a POST /chat/stream for each thing
 * said to it. Pairing, which is where the device token comes from, is in the pairing package.
 */
package dev.wobsoriano.muse

import java.security.SecureRandom
import kotlin.time.Duration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

open class MuseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Who the device says it is, to the app while pairing and to Muse afterwards. It is made once and
 * kept across unpairing. [mac] is shaped like a hardware address but is random, so it names no real
 * interface.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Identity(@EncodeDefault val mac: String = "") {
  /** Whether the identity is one [Identity.new] could have made. */
  val valid
    get() = MAC_SHAPE.matches(mac)

  private val suffix
    get() = mac.replace(":", "").let { if (it.length < 6) it else it.takeLast(6) }

  /** The name Muse knows the device by, like homelink-a1b2c3. */
  val nodeId
    get() = "homelink-$suffix"

  /**
   * The name to advertise while pairing, like MuseGadgetA1B2C3. There is no separator: the apps
   * compare what follows the prefix with what follows "homelink-" in the node id.
   */
  val bleName
    get() = "MuseGadget" + suffix.uppercase()

  /** The id the pairing protocol carries. */
  val deviceId
    get() = "hatch-link:$mac"

  companion object {
    private val MAC_SHAPE = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /** A fresh identity. */
    fun new(): Identity {
      val octets = ByteArray(6)
      SecureRandom().nextBytes(octets)
      // Unicast and locally administered, so it can never be mistaken for a vendor's address.
      octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte()
      return Identity(octets.joinToString(":") { "%02x".format(it.toInt() and 0xFF) })
    }
  }
}

/** What pairing leaves behind, in the shape the SDK keeps in pairing.json. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Credentials(
    @EncodeDefault @SerialName("access_token") val accessToken: String = "",
    @EncodeDefault @SerialName("refresh_token") val refreshToken: String = "",
    @EncodeDefault @SerialName("token_type") val tokenType: String = "",
    @EncodeDefault val username: String = "",
    /** Kept because the app sends it, and never read: only older firmware uses it. */
    @EncodeDefault @SerialName("api_url") val apiUrl: String = "",
    @EncodeDefault @SerialName("api_url_v2") val apiUrlV2: String = "",
    @EncodeDefault @SerialName("noise_host") val noiseHost: String = "",
    /** Unix seconds. */
    @EncodeDefault @SerialName("access_token_saved_at") val accessTokenSavedAt: Long = 0,
) {
  override fun toString() = "Credentials(username=$username, apiUrlV2=$apiUrlV2, noiseHost=$noiseHost, savedAt=$accessTokenSavedAt)"
}

/** Everything that has to outlive a restart. A null [credentials] is an unpaired device. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class State(
    @EncodeDefault val identity: Identity = Identity(),
    val credentials: Credentials? = null,
    /**
     * Set once Muse has been told the SDK token, which rides on a token refresh, so that a restart
     * does not rotate the tokens just to say it again.
     */
    @SerialName("sdk_token_reported") val sdkTokenReported: Boolean = false,
)

/**
 * Keeps the [State]. Refreshing the tokens kills the pair that was used, so [save] must not return
 * until the new State would survive a power cut: the client uses rotated tokens only after save has
 * accepted them. Both throw on failure.
 */
interface Store {
  suspend fun load(): State

  suspend fun save(state: State)
}

/** The JSON type of a command parameter. */
enum class ParamType(val json: kotlin.String) {
  String("string"),
  Integer("integer"),
  Boolean("boolean"),
}

data class Param(val name: kotlin.String, val type: ParamType, val description: kotlin.String = "")

/** A command's failure, reported to Muse with exactly this message. Anything else thrown is reported as a panic. */
class CommandFailed(message: String) : Exception(message)

/**
 * Runs a command. [args] is the JSON object Muse sent, {} when it sent none, and [timeout] the time it
 * has. What it returns is what Muse gets back, null being {}. Throwing [CommandFailed] reports the
 * failure; the coroutine is canceled when the time is up or the connection is gone.
 */
fun interface Handler {
  suspend fun handle(args: String, timeout: Duration): JsonElement?
}

/** Something Muse may ask the device to do. Muse reads [description] to decide when to call it. */
data class Command(
    val name: String,
    val description: String = "",
    val required: List<Param> = emptyList(),
    val optional: List<Param> = emptyList(),
    /** How long Muse should wait for it. Zero leaves Muse's default of 30 seconds. */
    val timeout: Duration = Duration.ZERO,
    val handler: Handler?,
)

/** One thing to say to Muse: words, or a recording of them. */
sealed interface AskInput {
  /** A typed message. */
  data class Text(val message: String) : AskInput

  /** A spoken message as a WAV file, which Muse transcribes itself. */
  class VoiceNote(val wav: ByteArray) : AskInput
}

/** News of an answer on its way. */
sealed interface ReplyEvent {
  /** What Muse made of a voice note. */
  data class Heard(val text: String) : ReplyEvent

  /** The whole answer so far, sent each time it grows. */
  data class Text(val text: String) : ReplyEvent

  /**
   * The answer once every message in it is complete and Muse has paused. It is almost always the
   * whole answer, a moment before ask returns to confirm it, so it is the time to start anything slow
   * such as speaking. If Muse does go on, another Settled carries the longer text.
   */
  data class Settled(val text: String) : ReplyEvent
}

/** A finished answer. [heard] is what Muse made of the voice note, and empty for a typed message. */
data class Reply(val text: String, val heard: String = "")

/** Where the connection stands. */
enum class Phase(val label: String) {
  /** Not connected, and [ConnState.error] says why if something went wrong. */
  Offline("offline"),
  /** Reaching Muse. */
  Connecting("connecting"),
  /** Registered: commands arrive and ask works. */
  Online("online"),
  /** No credentials, or Muse removed the device. */
  Unpaired("unpaired");

  override fun toString() = label
}

/** What a screen would show about the connection. [error] is only ever set when the phase is Offline. */
data class ConnState(val phase: Phase, val error: Throwable? = null)

/** Ask with no registered session to carry it. */
class OfflineException(detail: String? = null) :
    MuseException(if (detail == null) "muse: not connected" else "muse: not connected: $detail")

/** Ask while another is still being answered. Muse does not say which question an answer belongs to, so only one can be open. */
class BusyException : MuseException("muse: already asking")

/** What a [Client] needs. */
class Config(
    val store: Store,
    val commands: List<Command> = emptyList(),
    /** What the Muse app calls the device. */
    val displayName: String = "",
    /** The software version reported to Muse. */
    val version: String = "",
    /** The developer's SDK token, if there is one. It is sent to Muse once. */
    val sdkToken: String = "",
    /** Hears every change of connection state, on the coroutine running [Client.run]. */
    val onState: ((ConnState) -> Unit)? = null,
    val logger: Logger = Logger.None,
    /** The HTTP client to build on; tests use it to trust a fake server. */
    val http: OkHttpClient? = null,
)
