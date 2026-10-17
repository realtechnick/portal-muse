package dev.wobsoriano.museportal.protocol

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Raw bytes that travel as standard padded base64. */
@Serializable(with = BytesSerializer::class)
class Bytes(val array: ByteArray) {
  override fun equals(other: Any?) = other is Bytes && array.contentEquals(other.array)
  override fun hashCode() = array.contentHashCode()
  override fun toString() = "Bytes(${array.size})"
}

object BytesSerializer : KSerializer<Bytes> {
  override val descriptor = PrimitiveSerialDescriptor("Bytes", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: Bytes) =
      encoder.encodeString(Base64.getEncoder().encodeToString(value.array))
  override fun deserialize(decoder: Decoder) = Bytes(Base64.getDecoder().decode(decoder.decodeString()))
}

/** App to musecore. */
@Serializable
sealed interface Command {
  @Serializable
  @SerialName("config")
  data class Config(val name: String, val sdkToken: String, val ssid: String, val dns: List<String>) :
      Command {
    override fun toString() =
        "Config(name=$name, sdkToken=${if (sdkToken.isEmpty()) "<empty>" else "<set>"}, ssid=$ssid, dns=$dns)"
  }

  @Serializable @SerialName("pair.start") data object PairStart : Command

  @Serializable @SerialName("pair.stop") data object PairStop : Command

  @Serializable
  @SerialName("ble.advertising")
  data class BleAdvertising(val ok: Boolean, val error: String = "") : Command

  @Serializable @SerialName("ble.connected") data class BleConnected(val device: String) : Command

  @Serializable
  @SerialName("ble.write")
  data class BleWrite(val data: Bytes, val mtu: Int) : Command

  @Serializable @SerialName("ble.disconnected") data object BleDisconnected : Command

  /** Exactly one of [text] or [wav]; build it with [ofText] or [ofWav]. */
  @Serializable
  @SerialName("ask")
  data class Ask private constructor(val id: String, val text: String? = null, val wav: Bytes? = null) :
      Command {
    companion object {
      fun ofText(id: String, text: String) = Ask(id, text = text)
      fun ofWav(id: String, wav: ByteArray) = Ask(id, wav = Bytes(wav))
    }
  }

  @Serializable @SerialName("unpair") data object Unpair : Command
}

/** musecore to app. */
@Serializable
sealed interface Event {
  @Serializable
  @SerialName("status")
  data class Status(val phase: Phase, val error: String = "") : Event

  @Serializable
  @SerialName("pair.advertise")
  data class PairAdvertise(
      val name: String,
      val service: String,
      val rx: String,
      val tx: String,
      val manufacturerId: Int,
      val manufacturerData: Bytes,
  ) : Event

  @Serializable @SerialName("pair.unadvertise") data object PairUnadvertise : Event

  @Serializable @SerialName("ble.notify") data class BleNotify(val packets: List<Bytes>) : Event

  @Serializable @SerialName("ble.disconnect") data object BleDisconnect : Event

  @Serializable
  @SerialName("pair.status")
  data class PairStatus(val state: PairState, val name: String = "", val error: String = "") : Event

  @Serializable
  @SerialName("reply")
  data class Reply(val id: String, val kind: ReplyKind, val text: String = "", val error: String = "") :
      Event
}

@Serializable
enum class Phase {
  @SerialName("unpaired") Unpaired,
  @SerialName("connecting") Connecting,
  @SerialName("online") Online,
  @SerialName("offline") Offline,
}

@Serializable
enum class PairState {
  @SerialName("open") Open,
  @SerialName("connected") Connected,
  @SerialName("paired") Paired,
  @SerialName("failed") Failed,
  @SerialName("closed") Closed,
}

@Serializable
enum class ReplyKind {
  @SerialName("heard") Heard,
  @SerialName("text") Text,
  @SerialName("settled") Settled,
  @SerialName("done") Done,
  @SerialName("error") Error,
}

/** What one stdout line turned out to be. */
sealed interface Decoded {
  data class Known(val event: Event) : Decoded

  /** A well-formed message of a type this app does not know; the core may be newer. */
  data class Unknown(val type: String) : Decoded

  data class Malformed(val reason: String) : Decoded
}

/** The only place JSON is read or written. */
object Codec {
  private val json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  fun encode(command: Command): String = json.encodeToString(Command.serializer(), command)

  fun decode(line: String): Decoded {
    val obj =
        try {
          json.parseToJsonElement(line) as? JsonObject ?: return Decoded.Malformed("not an object")
        } catch (e: SerializationException) {
          return Decoded.Malformed(e.message ?: "bad JSON")
        }
    val type = (obj["type"] as? JsonPrimitive)?.content ?: return Decoded.Malformed("no type")
    if (type !in eventTypes) return Decoded.Unknown(type)
    return try {
      Decoded.Known(json.decodeFromJsonElement(Event.serializer(), obj))
    } catch (e: SerializationException) {
      Decoded.Malformed("$type: ${e.message}")
    } catch (e: IllegalArgumentException) {
      Decoded.Malformed("$type: ${e.message}")
    }
  }

  private val eventTypes: Set<String> =
      Event.serializer().descriptor.let { d ->
        // Sealed descriptors list their subclasses' serial names as the second element.
        val subclasses = d.getElementDescriptor(1)
        (0 until subclasses.elementsCount).map { subclasses.getElementName(it) }.toSet()
      }
}
