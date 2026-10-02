package io.github.tiltbob.grape.protocol.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan

/**
 * Wire-level constants and codecs for the older "ML" generation of Bebird scopes, which the
 * vendor app drives through its native `libBBCameraLibs.so` (M9 Pro, X17 Pro, Note3, T15,
 * P30, D3, E3, C3/T5, B1 and friends). The camera is the Wi-Fi access point and always has
 * the address [DEFAULT_HOST].
 *
 * Everything here is pure Kotlin so it can be unit-tested without Android.
 *
 * Three channels, all little-endian:
 *  - commands:  UDP [COMMAND_PORT] 50000, request/reply, 24-byte request header
 *  - video:     UDP [VIDEO_PORT] 8030, START/STOP + chunked JPEG frames (51-byte chunk header)
 *  - video:     TCP [TCP_VIDEO_PORT] 7060, `BoundaryS` + 32-byte header + JPEG + `BoundaryE`
 * The native library runs both video receivers at once and uses whichever delivers frames.
 */
object MlProtocol {
    const val DEFAULT_HOST = "192.168.10.123"
    const val COMMAND_PORT = 50000
    const val VIDEO_PORT = 8030
    const val TCP_VIDEO_PORT = 7060

    const val MAGIC: Int = 0x9999

    /** Request and reply header size on the command port. */
    const val COMMAND_LENGTH = 24

    /** Largest command reply the vendor library accepts (board info). */
    const val MAX_COMMAND_REPLY = 0x578

    /** Largest datagram on the video port. */
    const val MAX_VIDEO_DATAGRAM = 0xC00

    const val VIDEO_CHUNK_HEADER_LENGTH = 51
    const val TCP_FRAME_HEADER_LENGTH = 32
    const val MAX_FRAME_LENGTH = 1024 * 1024

    /** How often the video START must be repeated to keep the UDP stream flowing. */
    const val VIDEO_KEEPALIVE_MS = 600L

    // ---- command ids (second header short) ---------------------------------
    const val CMD_GET_VERSION = 0x1002
    const val CMD_GET_LIGHT = 0x1015
    const val CMD_SET_LIGHT = 0x1016
    const val CMD_GET_BATTERY = 0x1017
    const val CMD_POWER_OFF = 0x1019
    const val CMD_GET_STA_INFO = 0x101A
    const val CMD_GET_PWM_STATUS = 0x101C
    const val CMD_SET_PWM_STATUS = 0x101D
    const val CMD_SET_TWEEZER = 0x1054
    const val CMD_GET_BOARD_INFO = 0x1060

    // ---- video port control (same 24-byte shape, command 1 / 2) ------------
    const val VIDEO_START = 1
    const val VIDEO_STOP = 2

    /** Packet type of a UDP video chunk. */
    const val VIDEO_PACKET_TYPE = 3

    /**
     * Build a 24-byte request:
     * ```
     *  0  u16 magic 0x9999
     *  2  u16 command
     *  4  i32 sequence (any increasing number)
     *  8  u32 parameter (set commands), else 0
     * 12  zero padding to 24 bytes
     * ```
     */
    fun request(command: Int, sequence: Int, parameter: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(COMMAND_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(MAGIC.toShort())
        b.putShort(command.toShort())
        b.putInt(sequence)
        b.putInt(parameter)
        return b.array()
    }

    /** The video START/STOP datagrams carry the command in the second short and no sequence. */
    fun videoControl(command: Int): ByteArray = request(command, sequence = 0)

    /** True if [reply] echoes the magic and [command] we sent. */
    fun replyMatches(reply: ByteArray, length: Int, command: Int): Boolean {
        if (length < 4) return false
        val b = ByteBuffer.wrap(reply, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        return (b.getShort(0).toInt() and 0xFFFF) == MAGIC && (b.getShort(2).toInt() and 0xFFFF) == command
    }

    /** The 32-bit value most "get" replies carry at offset 8. */
    fun replyInt(reply: ByteArray, length: Int, offset: Int = 8): Int? {
        if (length < offset + 4) return null
        return ByteBuffer.wrap(reply, 0, length).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
    }

    data class Battery(
        /** Raw value from offset 8: bit 16 = charging, low 16 bits = millivolts. */
        val raw: Int,
        /** Optional direct percentage from offset 16 (present when bit 16 of that word is set). */
        val percentDirect: Int?,
    ) {
        val charging: Boolean get() = (raw ushr 16) and 1 != 0
        val millivolts: Int get() = raw and 0xFFFF

        /** Percent as the vendor app estimates it from the cell voltage (default curve). */
        fun percentEstimate(model: String? = null): Int {
            val m = model?.lowercase().orEmpty()
            val v = when {
                m.contains("note3") -> (millivolts - 3700) / 3f
                m == "r3" -> (millivolts - 3600) / 3f
                else -> (millivolts - 3400) / 6f
            }
            return v.toInt().coerceIn(0, 100)
        }
    }

    fun parseBattery(reply: ByteArray, length: Int): Battery? {
        if (length < 20) return null
        val b = ByteBuffer.wrap(reply, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val raw = b.getInt(8)
        val extra = b.getInt(16)
        val direct = if (extra and 0x10000 != 0) extra and 0xFFFF else null
        return Battery(raw, direct)
    }

    /**
     * Board-info reply:
     * ```
     *  0  u16 magic, 2 u16 command
     *  8  u32 checksum: XOR of the 1024 bytes at offset 24
     * 12  i32 length of the JSON document
     * 24  JSON document (NUL padded)
     * ```
     * Returns the JSON text or null if the checksum fails.
     */
    fun parseBoardInfo(reply: ByteArray, length: Int): String? {
        if (length < 24) return null
        val b = ByteBuffer.wrap(reply, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val checksum = b.getInt(8) and 0xFF
        val jsonLength = b.getInt(12)
        if (jsonLength <= 0 || 24 + jsonLength > length) return null
        var x = 0
        val end = minOf(length, 24 + 1024)
        for (i in 24 until end) x = x xor (reply[i].toInt() and 0xFF)
        if (x != checksum) return null
        val text = String(reply, 24, jsonLength, Charsets.UTF_8)
        val nul = text.indexOf('\u0000')
        return if (nul >= 0) text.substring(0, nul) else text
    }

    /**
     * One datagram of the UDP video stream:
     * ```
     *  2  u16 packetType       (3 = video chunk)
     *  8  u32 angleRaw         (orientation word, see [decodeAngle])
     * 16  u32 plainFlag        (0 = one byte of the frame is inverted, see [obfuscatedIndex])
     * 24  u8  frameKind        (3 = key / still frame that must not be dropped)
     * 25  u32 frameId
     * 29  u32 frameLength      (bytes of the whole JPEG)
     * 33  u16 chunkIndex       (1-based)
     * 35  u16 chunkCount
     * 37  u16 chunkLength      (payload bytes in this datagram)
     * 39  u32 timestamp
     * 47  u32 key              (input to [obfuscatedIndex])
     * 51  payload
     * ```
     */
    class VideoChunk(
        val packetType: Int,
        val angleRaw: Int,
        val plain: Boolean,
        val frameKind: Int,
        val frameId: Int,
        val frameLength: Int,
        val chunkIndex: Int,
        val chunkCount: Int,
        val chunkLength: Int,
        val timestamp: Int,
        val key: Int,
    )

    fun parseVideoChunk(data: ByteArray, length: Int): VideoChunk? {
        if (length < VIDEO_CHUNK_HEADER_LENGTH) return null
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val chunkLength = b.getShort(37).toInt() and 0xFFFF
        if (VIDEO_CHUNK_HEADER_LENGTH + chunkLength > length) return null
        return VideoChunk(
            packetType = b.getShort(2).toInt() and 0xFFFF,
            angleRaw = b.getInt(8),
            plain = b.getInt(16) != 0,
            frameKind = data[24].toInt() and 0xFF,
            frameId = b.getInt(25),
            frameLength = b.getInt(29),
            chunkIndex = b.getShort(33).toInt() and 0xFFFF,
            chunkCount = b.getShort(35).toInt() and 0xFFFF,
            chunkLength = chunkLength,
            timestamp = b.getInt(39),
            key = b.getInt(47),
        )
    }

    /**
     * The camera flips one byte of every UDP frame; this is its position. Port of the
     * library's `encode_index(key, length)`.
     */
    fun obfuscatedIndex(key: Int, length: Int): Int {
        if (length == 0) return 0
        val u = ((length xor key) + length + (length.inv() and 1)) xor length
        return Integer.remainderUnsigned(u, length)
    }

    /** Boundary markers of the TCP stream. */
    val TCP_START = "BoundaryS".toByteArray(Charsets.US_ASCII)
    val TCP_END = "BoundaryE".toByteArray(Charsets.US_ASCII)

    /**
     * Header of one TCP frame (follows `BoundaryS`):
     * ```
     *  1  u8  type (0 = video)
     *  2  u8  index (0 is treated as 1)
     *  4  u32 payload length
     * 12  u32 frame number
     * 20  u32 angleRaw
     * 24  u32 width, 28 u32 height (video only)
     * 32  payload: JPEG whose middle byte (payload[length / 2]) is inverted
     * ```
     */
    class TcpFrameHeader(
        val type: Int,
        val index: Int,
        val payloadLength: Int,
        val frameNumber: Int,
        val angleRaw: Int,
        val width: Int,
        val height: Int,
    )

    fun parseTcpFrameHeader(data: ByteArray, offset: Int, length: Int): TcpFrameHeader? {
        if (length - offset < TCP_FRAME_HEADER_LENGTH) return null
        val b = ByteBuffer.wrap(data, offset, TCP_FRAME_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        return TcpFrameHeader(
            type = data[offset + 1].toInt() and 0xFF,
            index = (data[offset + 2].toInt() and 0xFF).let { if (it == 0) 1 else it },
            payloadLength = b.getInt(offset + 4),
            frameNumber = b.getInt(offset + 12),
            angleRaw = b.getInt(offset + 20),
            width = b.getInt(offset + 24),
            height = b.getInt(offset + 28),
        )
    }

    /** Stateful port of the library's `getDegree()`; returns degrees, or null when unknown. */
    class AngleDecoder {
        private var lastStable = 0f
        private var lastSeen = 0f
        private var changes = 0

        fun decode(raw: Int): Float? {
            val top = raw ushr 30
            if (top >= 2) {
                // Absolute angle in thousandths of a degree.
                return ((raw and 0x3FFFFFFF) / 1000f) % 360f
            }
            if (raw == 0 || raw == 0x10001) return null
            // Three 10-bit sign-magnitude-ish accelerometer axes.
            val xi = raw and 0x3FF
            val yi = (raw ushr 10) and 0x3FF
            val zi = raw ushr 20
            val fx = if (xi < 0x200) xi.toFloat() else 1024f - xi
            val fy = if (yi < 0x200) yi.toFloat() else 1024f - yi
            val fz = if ((raw ushr 29) == 0) zi.toFloat() else 1024f - zi
            var a = atan((fy / fx).toDouble())
            if (xi > 0x200) a = PI - a
            if (yi > 0x200) a = 2 * PI - a
            val angle = a.toFloat()
            val threshold = if (fz < 25f) 0.04f else 0.009f
            if (kotlin.math.abs(angle - lastSeen) <= threshold) {
                changes = 0
            } else {
                lastSeen = angle
                changes++
            }
            val result = if (fx >= 64f || fy >= 64f) {
                if (angle == 0f || changes < 1) lastStable else { lastStable = angle; angle }
            } else {
                lastSeen = 0f
                lastStable
            }
            return Math.toDegrees(result.toDouble()).toFloat()
        }
    }
}
