package dev.wobsoriano.muse

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The vectors musecore wrote before it was removed (resources/vectors/README.md). */
object Fixtures {
  fun text(name: String): String =
      checkNotNull(Fixtures::class.java.getResource("/vectors/$name")) { "no fixture $name" }.readText()

  fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))
}

/** The Go side's detRand: SHA-256 of the seed and a big-endian counter, block after block. */
class DetRand(private val seed: String) : RandomSource {
  private var counter = 0L
  private var buf = ByteArray(0)
  private var at = 0

  override fun read(into: ByteArray) {
    var n = 0
    while (n < into.size) {
      if (at == buf.size) {
        val c = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        counter++
        buf = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray() + c)
        at = 0
      }
      val k = minOf(into.size - n, buf.size - at)
      buf.copyInto(into, n, at, at + k)
      at += k
      n += k
    }
  }

  fun bytes(n: Int) = ByteArray(n).also(::read)
}

fun String.unhex(): ByteArray = ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun ByteArray.sha256hex(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()

val JsonElement.obj: JsonObject
  get() = jsonObject

val JsonElement.arr: JsonArray
  get() = jsonArray

fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
