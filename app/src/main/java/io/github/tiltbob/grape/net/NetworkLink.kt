package io.github.tiltbob.grape.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import io.github.tiltbob.grape.camera.SocketBinder
import io.github.tiltbob.grape.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Holds the Android [Network] object for the camera's Wi-Fi.
 *
 * The scope's access point has no internet, so Android keeps routing the phone's traffic
 * over mobile data. Rather than binding the whole process, every socket the app opens is
 * bound to this network through [binder]. Ways to obtain it:
 *  - [connectTo]: join a specific scope found by [NearbyScanner] (what the vendor app's
 *    auto-connect does); on Android 10+ the connection exists only for this app;
 *  - [requestWifiBySsidPrefix]: Android 10+ picker limited to networks with a name prefix.
 */
class NetworkLink(context: Context) {

    private companion object {
        const val TAG = "NetworkLink"
        /**
         * Give up on a remembered scope that no radio heard (switched off, out of range)
         * after this long. A scope that was heard gets no timer: Android reports failure
         * itself, and a first-time approval dialog must not race a clock.
         */
        const val JOIN_TIMEOUT_MS = 30_000
    }

    enum class Status { NONE, REQUESTING, AVAILABLE, UNAVAILABLE, LOST }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val _status = MutableStateFlow(Status.NONE)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The SSID the current request targets, for display; null for a generic Wi-Fi request. */
    var targetSsid: String? = null
        private set

    /** Network id of a legacy (pre-Android 10) configuration we added, to clean up later. */
    private var legacyNetworkId = -1

    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestWifiBySsidPrefix(prefix: String) {
        targetSsid = null
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
            .build()
        DebugLog.log(TAG, "requestWifiBySsidPrefix(\"$prefix\")")
        request(specifier)
    }

    /**
     * Join the given scope's Wi-Fi. On Android 10+ this uses a [WifiNetworkSpecifier] with the
     * SSID and, when known, the BSSID, so the system connects without the user picking a
     * network (after a one-time approval that Android remembers). Older versions fall back to
     * a saved Wi-Fi configuration.
     */
    fun connectTo(camera: NearbyCamera) {
        targetSsid = camera.ssid
        DebugLog.log(TAG, "connectTo(ssid=${camera.ssid} bssid=${camera.bssid} security=${camera.security} ble=${camera.bleAddress})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request(specifierFor(camera), if (camera.heard) 0 else JOIN_TIMEOUT_MS)
        } else {
            connectLegacy(camera)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun specifierFor(camera: NearbyCamera): WifiNetworkSpecifier {
        val b = WifiNetworkSpecifier.Builder().setSsid(camera.ssid)
        camera.bssid?.let { mac -> runCatching { MacAddress.fromString(mac) }.getOrNull()?.let { b.setBssid(it) } }
        when (camera.security) {
            CameraWifi.Security.WPA2 -> b.setWpa2Passphrase(CameraWifi.DEFAULT_PASSPHRASE)
            CameraWifi.Security.WPA3 -> b.setWpa3Passphrase(CameraWifi.DEFAULT_PASSPHRASE)
            CameraWifi.Security.OPEN -> Unit
        }
        return b.build()
    }

    /**
     * Android 8/9: add (or reuse) a Wi-Fi configuration, enable it, then wait for the network.
     * Reading saved networks needs location permission there; without it we just add a new one.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectLegacy(camera: NearbyCamera) {
        val wm = appContext.getSystemService(WifiManager::class.java) ?: run {
            _status.value = Status.UNAVAILABLE
            return
        }
        val quoted = "\"" + camera.ssid + "\""
        val existing = runCatching { wm.configuredNetworks }.getOrNull()
            ?.firstOrNull { it.SSID == quoted }
        val id = existing?.networkId ?: run {
            val conf = WifiConfiguration().apply {
                SSID = quoted
                camera.bssid?.let { BSSID = it.lowercase() }
                priority = 40
                allowedKeyManagement.clear()
                if (camera.security == CameraWifi.Security.OPEN) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    preSharedKey = "\"" + CameraWifi.DEFAULT_PASSPHRASE + "\""
                }
            }
            wm.addNetwork(conf).also { legacyNetworkId = it }
        }
        if (id == -1) {
            _status.value = Status.UNAVAILABLE
            return
        }
        wm.enableNetwork(id, true)
        wm.reconnect()
        request(null)
    }

    private fun request(specifier: NetworkSpecifier?, timeoutMs: Int = 0) {
        release(keepTarget = true)
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (specifier != null) builder.setNetworkSpecifier(specifier)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _network.value = network
                _status.value = Status.AVAILABLE
                DebugLog.log(TAG, "onAvailable($network): ${describeNetwork()}")
            }

            override fun onLost(network: Network) {
                DebugLog.log(TAG, "onLost($network)")
                if (_network.value == network) {
                    _network.value = null
                    _status.value = Status.LOST
                }
            }

            override fun onUnavailable() {
                DebugLog.log(TAG, "onUnavailable()")
                _status.value = Status.UNAVAILABLE
            }
        }
        callback = cb
        _status.value = Status.REQUESTING
        try {
            if (timeoutMs > 0) cm.requestNetwork(builder.build(), cb, timeoutMs) else cm.requestNetwork(builder.build(), cb)
            DebugLog.log(TAG, "requestNetwork sent (specifier=${specifier != null}, timeout=${timeoutMs}ms)")
        } catch (e: Exception) {
            DebugLog.log(TAG, "requestNetwork failed", e)
            callback = null
            _status.value = Status.UNAVAILABLE
        }
    }

    fun release(keepTarget: Boolean = false) {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        _network.value = null
        _status.value = Status.NONE
        if (!keepTarget) targetSsid = null
    }

    /** Pins sockets to the linked network; a no-op when nothing is linked. */
    fun binder(): SocketBinder = SocketBinder(
        datagram = { socket ->
            val n = _network.value
            if (n == null) {
                DebugLog.log(TAG, "bind datagram socket: NO linked network, using the phone's default route")
            } else {
                runCatching { n.bindSocket(socket) }.onFailure { DebugLog.log(TAG, "bindSocket(datagram) failed", it) }
            }
        },
        stream = { socket ->
            val n = _network.value
            if (n == null) {
                DebugLog.log(TAG, "bind stream socket: NO linked network, using the phone's default route")
            } else {
                runCatching { n.bindSocket(socket) }.onFailure { DebugLog.log(TAG, "bindSocket(stream) failed", it) }
            }
        },
    )

    /** One-line summary of the linked network for the debug log. */
    fun describeNetwork(): String {
        val n = _network.value ?: return "  no network linked"
        val lp = cm.getLinkProperties(n)
        val caps = cm.getNetworkCapabilities(n)
        val sb = StringBuilder()
        sb.append("  interface=").append(lp?.interfaceName)
        sb.append(" addresses=").append(lp?.linkAddresses?.joinToString { it.toString() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) sb.append(" dhcp=").append(lp?.dhcpServerAddress?.hostAddress)
        sb.append(" routes=").append(lp?.routes?.joinToString { r -> "${r.destination}->${r.gateway?.hostAddress ?: "-"}" })
        sb.append(" dns=").append(lp?.dnsServers?.joinToString { it.hostAddress ?: "?" })
        sb.append(" wifi=").append(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        sb.append(" internet=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        sb.append(" validated=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        return sb.toString()
    }

    private fun linkProperties(): LinkProperties? = _network.value?.let { cm.getLinkProperties(it) }

    /** Our own IPv4 address on the linked network, for display. */
    fun localAddress(): String? = linkProperties()
        ?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }
        ?.address
        ?.hostAddress

    /**
     * Addresses that are likely to be the camera: the DHCP server and the default gateway of
     * the linked network. The scope is the access point, so it is both.
     */
    fun candidateHosts(): List<String> {
        val lp = linkProperties() ?: return emptyList()
        val out = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.dhcpServerAddress?.hostAddress?.let { out += it }
        }
        for (route in lp.routes) {
            val gw: InetAddress = route.gateway ?: continue
            if (gw is Inet4Address && !gw.isAnyLocalAddress) out += gw.hostAddress ?: continue
        }
        return out.toList()
    }
}
