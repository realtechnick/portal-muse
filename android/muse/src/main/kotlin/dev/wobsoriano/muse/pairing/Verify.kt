// This file is a modified port of the token check in linux/src/musegadget/cli.py and muse_api.py from
// Meta's Muse Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache
// License, Version 2.0.

package dev.wobsoriano.muse.pairing

import dev.wobsoriano.muse.fetch
import dev.wobsoriano.muse.goPrint
import dev.wobsoriano.muse.goString
import dev.wobsoriano.muse.parseObject
import dev.wobsoriano.muse.truthy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What setup yields: the device's tokens and where to use them, with the JSON names the SDK keeps them
 * under. The SDK also stamps access_token_saved_at when it writes them; that is the saver's to add.
 */
data class Result(
    val accessToken: String,
    val refreshToken: String,
    /** Always "device". */
    val tokenType: String,
    val username: String,
    /** Read only by older firmware; empty unless https. */
    val apiUrl: String,
    /** Where API paths go; empty means the Muse API. */
    val apiUrlV2: String,
    val noiseHost: String,
) {
  /** Leaves the tokens out, so a Result that ends up in a log line or an error gives nothing away. */
  override fun toString() = "pairing.Result{username=\"$username\" api_url_v2=\"$apiUrlV2\" noise_host=\"$noiseHost\"}"

  /** What API paths are appended to. */
  val apiRoot: String
    get() = if (apiUrlV2.isNotEmpty()) apiUrlV2.trimEnd('/') else "https://api.muse.ai"
}

private const val CHECK_TIMEOUT_SECONDS = 15L
private const val MAX_CHECK_BODY = 1 shl 20

class TokenCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Asks Muse whether a device token works, the way the SDK does before it saves one: the token must list
 * at least one computer the device can reach.
 */
class TokenCheck(http: OkHttpClient? = null, private val userAgent: String = "") {
  private val http = (http ?: OkHttpClient()).newBuilder().callTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

  /** Returns when Muse accepts [r]'s access token, and throws otherwise. */
  suspend fun verify(r: Result) {
    val request =
        try {
          Request.Builder()
              .url(r.apiRoot + "/fetch_vms")
              .get()
              .header("Authorization", "Bearer " + r.accessToken)
              .header("X-API-Version", "1.0.0")
              .apply { if (userAgent.isNotEmpty()) header("User-Agent", userAgent) }
              .build()
        } catch (e: IllegalArgumentException) {
          throw TokenCheckException("pairing: token check: ${e.message}")
        }
    val answer =
        try {
          http.fetch(request, MAX_CHECK_BODY)
        } catch (e: IOException) {
          throw TokenCheckException("pairing: token check: ${e.message}", e)
        }
    if (answer.status < 200 || answer.status > 299) throw TokenCheckException("pairing: token check: HTTP ${answer.status}")
    val body = parseObject(String(answer.body, Charsets.UTF_8)) ?: throw TokenCheckException("pairing: token check: not a JSON object")
    val list =
        when (val l = body["vm_list"]) {
          null,
          JsonNull -> emptyList()
          is JsonArray -> l
          else -> throw TokenCheckException("pairing: token check: vm_list is not a list")
        }
    val errorTitle = body["error_title"]
    val backendErrorCode = body["backend_error_code"]
    if (truthy(errorTitle) || truthy(backendErrorCode)) {
      throw TokenCheckException("pairing: token check: Muse answered ${goPrint(errorTitle)} ${goPrint(backendErrorCode)}")
    }
    for (raw in list) {
      val vm = raw as? JsonObject ?: continue
      val usable =
          try {
            (vm["vm_ws_url"].goString().isNotEmpty() || vm["vm_url"].goString().isNotEmpty()) && vm["vm_auth_token"].goString().isNotEmpty()
          } catch (_: Exception) {
            false
          }
      if (usable) return
    }
    throw TokenCheckException("pairing: token check: the token reaches no computer")
  }
}
