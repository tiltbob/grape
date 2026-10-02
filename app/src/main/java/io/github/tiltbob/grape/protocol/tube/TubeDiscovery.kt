package io.github.tiltbob.grape.protocol.tube

import io.github.tiltbob.grape.camera.CameraProtocol
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.SocketBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds tube-protocol cameras on the local Wi-Fi.
 *
 * Two independent mechanisms, both exactly what the vendor app does:
 *  - [probe]: broadcast the board-info request to port 58090 (plus the well-known AP
 *    addresses) and collect the JSON replies;
 *  - [listenForBeacons]: the scope broadcasts a JSON status beacon to port 58099 roughly ten
 *    times a second, so just listening finds it without sending anything.
 */
object TubeDiscovery {

    suspend fun probe(
        binder: SocketBinder,
        extraHosts: List<String>,
        timeoutMs: Long,
    ): List<DeviceInfo> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DeviceInfo>()
        val socket = try {
            DatagramSocket(null).apply {
                binder.datagram(this)
                reuseAddress = true
                bind(null)
                broadcast = true
                soTimeout = 200
            }
        } catch (e: IOException) {
            return@withContext emptyList()
        }
        socket.use { s ->
            val targets = (broadcastAddresses() + "255.255.255.255" + TubeProtocol.KNOWN_HOSTS + extraHosts)
                .distinct()
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            fun sendProbes() {
                val req = TubeProtocol.GET_BOARD_INFO
                for (t in targets) runCatching { s.send(DatagramPacket(req, req.size, t, TubeProtocol.COMMAND_PORT)) }
            }
            sendProbes()
            val partial = hashMapOf<InetAddress, ByteArrayOutputStream>()
            val buffer = ByteArray(TubeProtocol.MAX_DATAGRAM)
            val packet = DatagramPacket(buffer, buffer.size)
            val start = System.currentTimeMillis()
            var resent = false
            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    packet.length = buffer.size
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    if (!resent && System.currentTimeMillis() - start > timeoutMs / 2) {
                        sendProbes()
                        resent = true
                    }
                    continue
                } catch (e: IOException) {
                    break
                }
                val host = packet.address.hostAddress ?: continue
                if (host in found) continue
                val acc = partial.getOrPut(packet.address) { ByteArrayOutputStream() }
                acc.write(buffer, 0, packet.length)
                val bytes = acc.toByteArray()
                if (TubeProtocol.isBoardInfoComplete(bytes, bytes.size)) {
                    partial.remove(packet.address)
                    parseBoardInfo(host, bytes.toString(Charsets.UTF_8))?.let { found[host] = it }
                }
            }
        }
        found.values.toList()
    }

    suspend fun listenForBeacons(
        binder: SocketBinder,
        timeoutMs: Long,
    ): List<DeviceInfo> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DeviceInfo>()
        val socket = try {
            DatagramSocket(null).apply {
                binder.datagram(this)
                reuseAddress = true
                bind(InetSocketAddress(TubeProtocol.BEACON_PORT))
                soTimeout = 200
            }
        } catch (e: IOException) {
            return@withContext emptyList()
        }
        socket.use { s ->
            val buffer = ByteArray(TubeProtocol.MAX_DATAGRAM)
            val packet = DatagramPacket(buffer, buffer.size)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                try {
                    packet.length = buffer.size
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    break
                }
                val text = String(buffer, 0, packet.length, Charsets.UTF_8)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                val reported = json.optString("ipaddr")
                val host = if (reported.isNotBlank()) reported else packet.address.hostAddress
                if (host == null || host in found) continue
                found[host] = DeviceInfo(
                    host = host,
                    protocol = CameraProtocol.TUBE,
                    model = json.optString("model").ifBlank { null },
                    ssid = json.optString("ssid").ifBlank { null },
                    brand = json.optString("brand").ifBlank { null },
                )
            }
        }
        found.values.toList()
    }

    /** Turn a board-info JSON document into a [DeviceInfo]. */
    fun parseBoardInfo(host: String, text: String): DeviceInfo? {
        val json = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        return DeviceInfo(
            host = host,
            protocol = CameraProtocol.TUBE,
            model = json.optString("model").ifBlank { null },
            firmware = json.optString("firmware").ifBlank { null },
            ssid = json.optString("ssid").ifBlank { json.optString("ssid_prefix").ifBlank { null } },
            brand = json.optString("brand").ifBlank { null },
            floatAngle = json.optFlag("float_angle"),
            rotateAngle = json.optInt("rotate_angle", 0),
            rawInfo = text,
        )
    }

    /** The firmware writes some flags as booleans and some as 0/1. */
    private fun JSONObject.optFlag(key: String): Boolean {
        if (!has(key)) return false
        return when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            else -> false
        }
    }

    private fun broadcastAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast?.hostAddress }
    }.getOrDefault(emptyList())
}
