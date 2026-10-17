package dev.wobsoriano.museportal.device

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager

data class NetworkSnapshot(val ssid: String, val dns: List<String>)

/** The Wi-Fi network and DNS servers musecore needs in `config`; [onChange] fires when they move. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class NetworkInfo(context: Context, private val onChange: () -> Unit) {
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

  private val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()

        override fun onLost(network: Network) = onChange()
      }

  fun start() = connectivity.registerDefaultNetworkCallback(callback)

  fun stop() = runCatching { connectivity.unregisterNetworkCallback(callback) }

  fun snapshot(): NetworkSnapshot {
    val dns =
        runCatching {
              connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty().mapNotNull {
                it.hostAddress
              }
            }
            .getOrDefault(emptyList())
    return NetworkSnapshot(ssid(), dns)
  }

  private fun ssid(): String {
    val raw = runCatching { wifi?.connectionInfo?.ssid }.getOrNull() ?: return ""
    if (raw == WifiManager.UNKNOWN_SSID) return ""
    return raw.removeSurrounding("\"")
  }
}
