package io.ahmed.sysmon.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds a reference to the current Wi-Fi network so OkHttp can pin sockets to it,
 * bypassing any active VPN that would otherwise hijack the route to LAN IPs like
 * the router at 192.168.100.1.
 *
 * Without this, on a phone with an active VPN (e.g. a VPN app), our OkHttp call to
 * the router fails with EHOSTUNREACH because the VPN advertises a /32 route to the
 * router's IP and steals the traffic.
 */
object WifiNetworkBinder {

    private val current = AtomicReference<Network?>(null)
    @Volatile private var registered = false

    fun current(): Network? = current.get()

    fun ensureStarted(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)  // some routers fail captive check
                .build()

            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    current.set(network)
                }
                override fun onLost(network: Network) {
                    if (current.get() == network) current.set(null)
                }
                override fun onUnavailable() {
                    current.set(null)
                }
            })

            // Seed synchronously so the first poll after boot can already use it.
            cm.activeNetwork?.let { net ->
                val caps = cm.getNetworkCapabilities(net)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    current.set(net)
                }
            } ?: run {
                // Scan all networks — activeNetwork may be VPN; we want wlan0 specifically
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        current.set(n)
                        break
                    }
                }
            }

            registered = true
        }
    }

    /**
     * Run [block] with the whole process temporarily bound to the Wi-Fi network —
     * forces every socket created inside the block to use wlan0 even when a VPN
     * is active. Always unbinds on exit.
     *
     * Returns the block's result, or null if we had no Wi-Fi to bind to (caller
     * may decide to try anyway with the system default route).
     */
    inline fun <T> withWifiProcess(context: Context, block: () -> T): T? {
        ensureStarted(context)
        val net = current() ?: return null
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val bound = if (Build.VERSION.SDK_INT >= 23) {
            cm.bindProcessToNetwork(net)
        } else {
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(net)
        }
        return try {
            block()
        } finally {
            if (bound) {
                if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(null)
                else @Suppress("DEPRECATION") ConnectivityManager.setProcessDefaultNetwork(null)
            }
        }
    }
}
