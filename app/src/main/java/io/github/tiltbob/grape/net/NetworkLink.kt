package io.github.tiltbob.grape.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.tiltbob.grape.camera.SocketBinder
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Holds the Android [Network] object for the camera's Wi-Fi.
 *
 * The scope's access point has no internet, so Android keeps routing the phone's traffic
 * over mobile data. Rather than binding the whole process, every socket the app opens is
 * bound to this network through [binder]. Two ways to obtain it:
 *  - [requestWifi]: whatever Wi-Fi the user already joined from system settings
 *  - [requestWifiBySsidPrefix]: Android 10+ picker limited to networks whose name starts
 *    with a prefix; the connection then exists only for this app.
 */
class NetworkLink(context: Context) {

    enum class Status { NONE, REQUESTING, AVAILABLE, UNAVAILABLE, LOST }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val _status = MutableStateFlow(Status.NONE)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun requestWifi() = request(null)

    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestWifiBySsidPrefix(prefix: String) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
            .build()
        request(specifier)
    }

    private fun request(specifier: NetworkSpecifier?) {
        release()
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (specifier != null) builder.setNetworkSpecifier(specifier)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _network.value = network
                _status.value = Status.AVAILABLE
            }

            override fun onLost(network: Network) {
                if (_network.value == network) {
                    _network.value = null
                    _status.value = Status.LOST
                }
            }

            override fun onUnavailable() {
                _status.value = Status.UNAVAILABLE
            }
        }
        callback = cb
        _status.value = Status.REQUESTING
        try {
            cm.requestNetwork(builder.build(), cb)
        } catch (e: Exception) {
            callback = null
            _status.value = Status.UNAVAILABLE
        }
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        _network.value = null
        _status.value = Status.NONE
    }

    /** Pins sockets to the linked network; a no-op when nothing is linked. */
    fun binder(): SocketBinder = SocketBinder(
        datagram = { socket -> _network.value?.let { runCatching { it.bindSocket(socket) } } },
        stream = { socket -> _network.value?.let { runCatching { it.bindSocket(socket) } } },
    )

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
