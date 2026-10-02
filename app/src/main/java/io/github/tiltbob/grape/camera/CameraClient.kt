package io.github.tiltbob.grape.camera

import io.github.tiltbob.grape.net.CameraWifi

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/** Which wire protocol a camera speaks. */
enum class CameraProtocol {
    /** Pure-UDP "tube" protocol on ports 58080/58090 (ES, ESU, UltraX, Note5, R1, R3...). */
    TUBE,

    /** Older "ML" protocol implemented by the vendor's native library (M9 Pro, X17 Pro, Note3, T15...). */
    ML,
}

/** What we know about a camera before and after connecting. */
data class DeviceInfo(
    val host: String,
    val protocol: CameraProtocol,
    val model: String? = null,
    val firmware: String? = null,
    val ssid: String? = null,
    val brand: String? = null,
    /** Frame-header angle uses the tenth-of-a-degree encoding. */
    val floatAngle: Boolean = false,
    /** Fixed rotation the vendor app applies for this unit, in degrees. */
    val rotateAngle: Int = 0,
    /** The raw board-info JSON document, if the device reported one. */
    val rawInfo: String? = null,
) {
    /** `Bebird R1`: from the board info when we have it, else from the Wi-Fi name, else the address. */
    val displayName: String
        get() = CameraWifi.prettyName(brand, model) ?: CameraWifi.prettyName(ssid).ifBlank { host }
}

/** One decoded-ready JPEG frame plus the roll angle reported with it. */
class VideoFrame(
    val jpeg: ByteArray,
    val angleDegrees: Float,
    val receivedAtNanos: Long,
)

data class BatteryStatus(
    val percent: Int,
    val charging: Boolean,
    /** Protocol-specific raw state value, for diagnostics. */
    val rawState: Int,
)

enum class ConnectionState { IDLE, CONNECTED, STREAMING, CLOSED }

/**
 * A live link to one camera. Implementations talk only to [DeviceInfo.host]; nothing else
 * on the network is ever contacted.
 */
interface CameraClient : Closeable {
    val info: DeviceInfo
    val frames: SharedFlow<VideoFrame>
    val battery: StateFlow<BatteryStatus?>
    val state: StateFlow<ConnectionState>

    /** Open sockets. Must be called before [start]. */
    suspend fun connect()

    /** Start the video stream (and whatever keep-alive the device needs). */
    suspend fun start()

    /** Stop the video stream but keep the connection. */
    suspend fun stop()

    /** Tip light level, 0..100. */
    suspend fun setLight(percent: Int)

    /** Current tip light level, or null if the device does not report it. */
    suspend fun getLight(): Int?

    override fun close()
}
