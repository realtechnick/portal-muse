// This file is a modified port of linux/src/musegadget/network.py from Meta's Muse Gadget SDK,
// Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.

package dev.wobsoriano.muse.pairing

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry of the scan the phone asks for. */
data class WiFiNetwork(val ssid: String, val rssi: Int, val secure: Boolean)

/**
 * How the device gets online during setup. The apps always scan and always send an SSID back, so a
 * device has to answer both even when it has nothing to join. [scan] and [join] run off the
 * Controller's loop and may take a while; [online] is asked on it and should not.
 */
interface Network {
  /** Whether the device can reach Muse now. */
  suspend fun online(): Boolean

  /** The networks to offer the phone. */
  suspend fun scan(): List<WiFiNetwork>

  /** Gets the device online with what the phone chose, and returns once it is; throws when it cannot. */
  suspend fun join(ssid: String, password: String)
}

/** [AlreadyOnline]'s answer when the device is not online after all. */
class OfflineException : Exception("pairing: the device is not online")

/** Names the offered network when the device does not know its SSID. */
const val CURRENT_CONNECTION_LABEL = "Use current connection"

private const val API_HOST = "api.muse.ai"
private const val ONLINE_TIMEOUT_MS = 5000

/**
 * The Network of a device that was put on Wi-Fi some other way before setup, which is how the Linux
 * SDK works. It offers exactly one network, standing for the connection it has, marked open so the
 * apps skip the password field, and ignores the Wi-Fi fields that come back.
 */
class AlreadyOnline(
    /** Whether Muse can be reached. Null tries a TCP connection to the Muse API. */
    private val reachable: (suspend () -> Boolean)? = null,
    /**
     * The network the device is on, so the app can preselect it when the phone is on the same one. Null
     * or an empty answer offers [CURRENT_CONNECTION_LABEL].
     */
    private val ssid: (() -> String)? = null,
) : Network {
  override suspend fun online(): Boolean {
    reachable?.let { return it() }
    return withContext(Dispatchers.IO) {
      try {
        Socket().use { it.connect(InetSocketAddress(API_HOST, 443), ONLINE_TIMEOUT_MS) }
        true
      } catch (_: IOException) {
        false
      }
    }
  }

  override suspend fun scan(): List<WiFiNetwork> {
    if (!online()) return emptyList()
    val name = ssid?.invoke().orEmpty().ifEmpty { CURRENT_CONNECTION_LABEL }
    return listOf(WiFiNetwork(name, -40, false))
  }

  override suspend fun join(ssid: String, password: String) {
    if (!online()) throw OfflineException()
  }
}
