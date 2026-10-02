package io.github.tiltbob.grape.protocol.ml

import io.github.tiltbob.grape.camera.CameraProtocol
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.SocketBinder
import io.github.tiltbob.grape.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds ML-protocol cameras. There is no broadcast discovery in this generation: the camera
 * is always the access point at [MlProtocol.DEFAULT_HOST], so we simply ask it (and the
 * gateway of the linked Wi-Fi, in case the address differs) for its board info.
 */
object MlDiscovery {
    private const val TAG = "MlDiscovery"

    suspend fun probe(binder: SocketBinder, extraHosts: List<String>): List<DeviceInfo> = coroutineScope {
        val hosts = (listOf(MlProtocol.DEFAULT_HOST) + extraHosts).distinct()
        hosts.map { host -> async { probeHost(binder, host) } }.awaitAll().filterNotNull()
    }

    suspend fun probeHost(binder: SocketBinder, host: String): DeviceInfo? = withContext(Dispatchers.IO) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext null
        val socket = try {
            DatagramSocket(null).apply {
                binder.datagram(this)
                bind(null)
                soTimeout = 200
            }
        } catch (e: IOException) {
            DebugLog.log(TAG, "probe $host: could not open socket", e)
            return@withContext null
        }
        socket.use { s ->
            val raw = exchange(s, address, MlProtocol.CMD_GET_BOARD_INFO, MlProtocol.MAX_COMMAND_REPLY)
            val boardInfo = raw?.let { (buf, n) -> MlProtocol.parseBoardInfo(buf, n) }
            DebugLog.log(TAG, "probe $host: board info reply=${raw?.second ?: "none"} bytes, parsed=${boardInfo != null}${boardInfo?.let { ": " + it.take(200) } ?: ""}")
            if (boardInfo != null) return@withContext fromBoardInfo(host, boardInfo)
            val version = exchange(s, address, MlProtocol.CMD_GET_VERSION, 0x400)
                ?.let { (buf, n) -> MlProtocol.replyInt(buf, n) }
            DebugLog.log(TAG, "probe $host: version reply=${version ?: "none"}")
            version ?: return@withContext null
            DeviceInfo(host = host, protocol = CameraProtocol.ML, firmware = version.toString())
        }
    }

    fun fromBoardInfo(host: String, text: String): DeviceInfo {
        val json = runCatching { JSONObject(text) }.getOrNull()
        return DeviceInfo(
            host = host,
            protocol = CameraProtocol.ML,
            model = json?.optString("model")?.ifBlank { null },
            firmware = json?.optString("hardware")?.ifBlank { null },
            brand = json?.optString("brand")?.ifBlank { null },
            rawInfo = text,
        )
    }

    private fun exchange(
        socket: DatagramSocket,
        address: InetAddress,
        command: Int,
        replySize: Int,
        attempts: Int = 3,
    ): Pair<ByteArray, Int>? {
        val buffer = ByteArray(replySize)
        val packet = DatagramPacket(buffer, buffer.size)
        repeat(attempts) { attempt ->
            val request = MlProtocol.request(command, attempt + 1)
            try {
                socket.send(DatagramPacket(request, request.size, address, MlProtocol.COMMAND_PORT))
                packet.length = buffer.size
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                return@repeat
            } catch (e: IOException) {
                DebugLog.log(TAG, "cmd 0x${Integer.toHexString(command)} to ${address.hostAddress}: io error", e)
                return null
            }
            if (MlProtocol.replyMatches(buffer, packet.length, command)) return buffer to packet.length
            DebugLog.log(TAG, "cmd 0x${Integer.toHexString(command)}: unexpected ${packet.length} bytes from ${packet.address.hostAddress}: ${DebugLog.hex(buffer, packet.length)}")
        }
        return null
    }
}
