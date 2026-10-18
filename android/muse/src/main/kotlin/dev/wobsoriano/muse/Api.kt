// Ported from linux/src/musegadget/muse_api.py in Meta's Muse Gadget SDK, Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE at the
// root of this module). Modified: rewritten in Kotlin, and a failure is returned to the caller where the SDK
// logged it.

package dev.wobsoriano.muse

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

internal const val DEFAULT_API_ROOT = "https://api.muse.ai"
private const val FETCH_PATH = "/fetch_vms"
private const val REFRESH_PATH = "/device_token/refresh"
internal val API_TIMEOUT = 15.seconds
private const val MAX_API_BODY = 256 shl 10

/** One of the owner's Muse VMs, with the bearer that opens a session to it. */
internal class Vm(
    val id: String,
    val name: String,
    val authToken: String,
    val isDefault: Boolean,
    /**
     * How to ask again when the VM's front door answers 403: the API says how long to wait and how
     * many times, beside the list. Zero is not at all.
     */
    val knockEvery: Duration,
    val knocks: Int,
) {
  override fun toString() = "Vm(id=$id, name=$name)"
}

// The most the API's word on asking again is taken at: eighty times, never faster than ten times a
// second.
private val MIN_KNOCK_EVERY = 100.milliseconds
private val MAX_KNOCK_EVERY = 5.seconds
private const val MAX_KNOCKS = 80

internal class TokenPair(val accessToken: String, val refreshToken: String)

/**
 * A call that failed. [status] is 0 when no answer came, which is worth trying again; any other
 * status is the server's own word.
 */
internal class ApiException(val status: Int, message: String, cause: Throwable? = null) : MuseException(message, cause)

/** Raised where Go's json.Unmarshal into a typed struct would have failed. */
internal class ShapeException : Exception("the JSON is not the expected shape")

internal fun JsonElement?.goString(): String =
    when {
      this == null || this == JsonNull -> ""
      this is JsonPrimitive && isString -> content
      else -> throw ShapeException()
    }

internal fun JsonElement?.goInt(): Long =
    when {
      this == null || this == JsonNull -> 0
      this is JsonPrimitive && !isString -> content.toLongOrNull() ?: throw ShapeException()
      else -> throw ShapeException()
    }

internal fun JsonElement?.goBool(): Boolean =
    when {
      this == null || this == JsonNull -> false
      this is JsonPrimitive && !isString && (content == "true" || content == "false") -> content == "true"
      else -> throw ShapeException()
    }

/** A JSON value decoded into Go's `any` and tested as the SDK tests it. */
internal fun truthy(v: JsonElement?): Boolean =
    when (v) {
      null,
      JsonNull -> false
      is JsonPrimitive ->
          when {
            v.isString -> v.content.isNotEmpty()
            v.content == "true" || v.content == "false" -> v.content == "true"
            else -> v.content.toDoubleOrNull()?.let { it != 0.0 } ?: true
          }
      else -> true
    }

/** What Go's %v prints for a decoded `any`, for an error message. */
internal fun goPrint(v: JsonElement?): String =
    when (v) {
      null,
      JsonNull -> "<nil>"
      is JsonPrimitive -> v.content
      else -> v.toString()
    }

internal class Answer(val status: Int, val body: ByteArray)

/** Sends [request], reads at most [max] bytes of the answer, and cancels the call if the coroutine is. */
internal suspend fun OkHttpClient.fetch(request: Request, max: Int): Answer =
    suspendCancellableCoroutine { cont ->
      val call = newCall(request)
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(
          object : Callback {
            override fun onFailure(call: Call, e: IOException) {
              cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
              val answer =
                  try {
                    response.use { Answer(it.code, it.body?.readAtMost(max) ?: EMPTY) }
                  } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                  }
              cont.resume(answer)
            }
          })
    }

private fun ResponseBody.readAtMost(max: Int): ByteArray {
  val source = source()
  source.request(max.toLong())
  val n = minOf(source.buffer.size, max.toLong())
  return source.buffer.readByteArray(n)
}

internal class ApiClient(private val http: OkHttpClient, private val userAgent: String) {
  private suspend fun call(builder: Request.Builder): Answer =
      try {
        http.fetch(builder.header("User-Agent", userAgent).build(), MAX_API_BODY)
      } catch (e: IOException) {
        throw ApiException(0, e.message ?: e.toString(), e)
      }

  /**
   * Lists the VMs the device token can reach. A 401 means the token was rejected, which trying
   * again will not fix.
   */
  suspend fun fetchVms(root: String, accessToken: String): List<Vm> {
    val answer =
        try {
          call(Request.Builder().url(root + FETCH_PATH).get().header("Authorization", "Bearer $accessToken").header("X-API-Version", "1.0.0"))
        } catch (e: ApiException) {
          throw ApiException(0, "muse: VM fetch: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
          throw ApiException(0, "muse: VM fetch: ${e.message}", e)
        }
    if (answer.status != 200) throw ApiException(answer.status, "muse: VM fetch: HTTP ${answer.status}")
    try {
      val out = parseObject(String(answer.body, Charsets.UTF_8)) ?: throw ShapeException()
      val errorTitle = out["error_title"]
      val backendErrorCode = out["backend_error_code"]
      val retryAfterMs = out["retry_after_ms"].goInt()
      val maxRetryCount = out["max_retry_count"].goInt()
      val list =
          when (val l = out["vm_list"]) {
            null,
            JsonNull -> emptyList()
            is JsonArray -> l.map { it as? JsonObject ?: if (it == JsonNull) JsonObject(emptyMap()) else throw ShapeException() }
            else -> throw ShapeException()
          }
      class Entry(val url: String, val wsUrl: String, val authToken: String, val name: String, val id: String, val isDefault: Boolean)
      val entries =
          list.map {
            Entry(it["vm_url"].goString(), it["vm_ws_url"].goString(), it["vm_auth_token"].goString(), it["vm_name"].goString(), it["vm_id"].goString(), it["default"].goBool())
          }
      if (truthy(errorTitle) || truthy(backendErrorCode)) {
        throw ApiException(answer.status, "muse: VM fetch: ${goPrint(errorTitle)} ${goPrint(backendErrorCode)}")
      }
      val knocks = maxRetryCount.coerceIn(0, MAX_KNOCKS.toLong()).toInt()
      val every = if (knocks > 0) retryAfterMs.milliseconds.coerceIn(MIN_KNOCK_EVERY, MAX_KNOCK_EVERY) else Duration.ZERO
      return entries
          .filter { (it.wsUrl.isNotEmpty() || it.url.isNotEmpty()) && it.authToken.isNotEmpty() }
          .map { Vm(it.id, it.name, it.authToken, it.isDefault, every, knocks) }
    } catch (e: ShapeException) {
      throw ApiException(answer.status, "muse: VM fetch: not an answer: ${e.message}")
    }
  }

  /**
   * Trades the refresh token for a new pair, and the old pair is dead from then on. A 401 means the
   * pairing is gone and the device must be paired again.
   *
   * The access token is never presented: the server can accept it and answer 200 with replacement
   * tokens that every endpoint then rejects, which would overwrite working credentials.
   */
  suspend fun refresh(root: String, refreshToken: String, nodeId: String, sdkToken: String): TokenPair {
    // Apps now hand over refresh tokens that already carry the prefix, and doubling it makes the
    // server reject the token.
    val raw = refreshToken.substring(refreshToken.lastIndexOf(':') + 1)
    val body = buildJsonObject {
      put("device_id", nodeId)
      if (sdkToken.isNotEmpty()) put("sdk_token", sdkToken)
    }
    val answer =
        try {
          call(
              Request.Builder()
                  .url(root + REFRESH_PATH)
                  .post(body.toString().toRequestBody("application/json".toMediaType()))
                  .header("Authorization", "Bearer hatch_refresh:$raw")
                  .header("Content-Type", "application/json"))
        } catch (e: ApiException) {
          throw ApiException(0, "muse: token refresh: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
          throw ApiException(0, "muse: token refresh: ${e.message}", e)
        }
    if (answer.status != 200) throw ApiException(answer.status, "muse: token refresh: HTTP ${answer.status}")
    val pair =
        try {
          val out = parseObject(String(answer.body, Charsets.UTF_8)) ?: throw ShapeException()
          fun pairOf(o: JsonObject) = TokenPair(o["access_token"].goString(), o["refresh_token"].goString())
          val top = pairOf(out)
          when (val p = out["payload"]) {
            null,
            JsonNull -> top
            is JsonObject -> pairOf(p)
            else -> throw ShapeException()
          }
        } catch (e: ShapeException) {
          throw ApiException(0, "muse: token refresh: not an answer: ${e.message}")
        }
    if (pair.accessToken.isEmpty() || pair.refreshToken.isEmpty()) {
      throw ApiException(answer.status, "muse: token refresh: the answer has no tokens")
    }
    return pair
  }
}

/** Where the API paths hang. Credentials.apiUrl is not consulted: only older firmware reads it. */
internal fun apiRoot(c: Credentials, allowPlaintext: Boolean): String {
  val root = c.apiUrlV2.trimEnd('/')
  if (root.isEmpty()) return DEFAULT_API_ROOT
  if (root.startsWith("https://") || (allowPlaintext && root.startsWith("http://"))) return root
  // The device token goes to this address, so it does not go anywhere in the clear.
  throw MuseException("muse: the API address in the credentials is not https")
}
