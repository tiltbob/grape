package io.github.tiltbob.grape.protocol.tube

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-level constants and codecs for the "tube" generation of Bebird Wi-Fi scopes
 * (the devices the vendor app drives from its pure-Java `BebirdTube` class:
 * ES, ESU, UltraX, Note5, R1, R3, M9, X17 and friends on the 192.168.5.1 /
 * 192.168.10.1 access points).
 *
 * Everything here is pure Kotlin so it can be unit-tested without Android.
 *
 * Transport: UDP, all to the camera's IP.
 *  - [VIDEO_PORT]   58080  start/stop video and MJPEG chunk stream
 *  - [COMMAND_PORT] 58090  request/reply commands (battery, board info, light...)
 *  - [SENSOR_PORT]  58098  optional orientation sensor stream
 *  - [BEACON_PORT]  58099  JSON status beacon broadcast by the camera
 */
object TubeProtocol {
    const val VIDEO_PORT = 58080
    const val COMMAND_PORT = 58090
    const val SENSOR_PORT = 58098
    const val BEACON_PORT = 58099

    /** Access-point addresses the vendor app hard-codes for this generation. */
    val KNOWN_HOSTS = listOf("192.168.5.1", "192.168.10.1")

    /** Datagram payload limit used by the camera. */
    const val MAX_DATAGRAM = 4096

    /** Header prepended to each MJPEG chunk on the video port. */
    const val FRAME_HEADER_LENGTH = 4

    /** Upper bound for one reassembled JPEG frame. */
    const val MAX_FRAME_LENGTH = 512 * 1024

    // ---- video port (58080) -------------------------------------------------
    val START_VIDEO = byteArrayOf(0x20, 0x36)
    val STOP_VIDEO = byteArrayOf(0x20, 0x37)

    // ---- command port (58090) -----------------------------------------------
    /** Reply is a JSON document that may span several datagrams; it ends with '}'. */
    val GET_BOARD_INFO = byteArrayOf(0x66, 0x39, 0x01, 0x01)

    /** Reply is a 4-byte big-endian int: high 16 bits = state, low 16 bits = percent. */
    val GET_BATTERY = byteArrayOf(0x66, 0x3A)

    /** Set the tip light level 0..100. Takes effect once [COMMIT_BRIGHTNESS] is sent. */
    fun setBrightness(level: Int): ByteArray {
        require(level in 0..100) { "brightness must be 0..100, was $level" }
        return byteArrayOf(0x66, 0x3C, level.toByte())
    }

    /** Apply the previously sent brightness level. */
    val COMMIT_BRIGHTNESS = byteArrayOf(0x66, 0x3C, 0xFF.toByte())

    /** Ask for the current brightness; reply is one byte 0..100. */
    val QUERY_BRIGHTNESS = byteArrayOf(0x66, 0x3C, 0xFE.toByte())

    /** Camera image effect selector (0 = normal). */
    fun setCameraEffect(effect: Int) = byteArrayOf(0x66, 0x3B, effect.toByte())

    /**
     * LED trigger. The vendor app sends (0, 1) when the camera screen opens and (0, 0)
     * when it closes, which turns the tip light on at the saved level / off.
     * (2, 1) / (2, 0) drive the blue status LED.
     */
    fun triggerLed(which: Int, on: Boolean) =
        byteArrayOf(0x66, 0x3F, which.toByte(), if (on) 1 else 0)

    /** Named "reboot" in the vendor app; on most units it powers the scope off. */
    val REBOOT = byteArrayOf(0x66, 0x3E)

    /** Tweezer / accessory status (1 = plain tip, 2 = tweezer tip). */
    fun setTweezerStatus(status: Int) = byteArrayOf(0x66, 0x40, status.toByte())

    // ---- sensor port (58098) ------------------------------------------------
    /** Start (1) or stop (0) the 34-byte orientation sensor stream. */
    fun toggleAngleStream(on: Boolean) = byteArrayOf(0x86.toByte(), 0x06, if (on) 1 else 0)

    /** Index of the roll angle (0..359) inside the sensor packet when read as big-endian shorts. */
    const val SENSOR_ANGLE_INDEX = 9
    const val SENSOR_PACKET_LENGTH = 34

    // ---- codecs -------------------------------------------------------------

    data class Battery(val state: Int, val percent: Int) {
        val charging: Boolean get() = state == 2
        val full: Boolean get() = state == 3
        /** State 4 means the scope is powering down / handing off. */
        val disconnecting: Boolean get() = state == 4
    }

    /** Decode a battery reply. Returns null if the datagram is too short. */
    fun parseBattery(data: ByteArray, length: Int = data.size): Battery? {
        if (length < 4) return null
        val v = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        return Battery(state = (v ushr 16) and 0xFFFF, percent = v and 0xFFFF)
    }

    /** Decode the roll angle from a sensor datagram, or null if it is not a valid packet. */
    fun parseSensorAngle(data: ByteArray, length: Int = data.size): Int? {
        if (length < (SENSOR_ANGLE_INDEX + 1) * 2) return null
        val shorts = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
        val angle = shorts.get(SENSOR_ANGLE_INDEX).toInt()
        return if (angle in 0..359) angle else null
    }

    /** True once a (possibly multi-datagram) board-info reply is complete. */
    fun isBoardInfoComplete(buffer: ByteArray, length: Int): Boolean =
        length > 0 && buffer[length - 1] == '}'.code.toByte()
}
