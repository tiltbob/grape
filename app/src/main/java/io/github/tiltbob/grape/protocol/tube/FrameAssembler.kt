package io.github.tiltbob.grape.protocol.tube

import java.util.BitSet

/**
 * Reassembles JPEG frames from the chunked datagrams the camera sends on the video port.
 *
 * Every datagram is:
 * ```
 *  byte 0   frame id            (all chunks of one frame share it; wraps at 255)
 *  byte 1   last-chunk flag     (0 = more chunks follow; non-zero = final chunk,
 *                                also carries angle high bits, see below)
 *  byte 2   chunk index         (1-based)
 *  byte 3   roll angle, low 8 bits (only meaningful on the final chunk)
 *  byte 4.. JPEG data
 * ```
 * Chunks of one frame are all the same size except the last one, so the payload size of
 * chunk 1 fixes where every later chunk lands in the frame buffer. A frame is delivered only
 * when every chunk index from 1 to the final one was seen.
 *
 * Angle encoding on the final chunk:
 *  - integer mode (default):  `angle = b3 + (b1 == 2 ? 256 : 0)`  -> 0..359 degrees
 *  - float mode (`float_angle` board flag): `angle = (b3 + (b1 != 15 ? b1 << 8 : 0)) / 10`
 */
class FrameAssembler(
    @Volatile var floatAngle: Boolean = false,
    maxFrameLength: Int = TubeProtocol.MAX_FRAME_LENGTH,
) {
    /** One complete JPEG frame. [data] is valid for [length] bytes starting at offset 0. */
    class Frame(val data: ByteArray, val length: Int, val angleDegrees: Float, val frameId: Int) {
        fun toByteArray(): ByteArray = data.copyOf(length)
    }

    private val frame = ByteArray(maxFrameLength)
    private val received = BitSet()
    private var frameId = -1
    private var chunkSize = 0
    private var lastIndex = -1
    private var endOffset = -1
    private var angle = 0f

    var framesCompleted = 0L
        private set
    var framesDropped = 0L
        private set

    fun reset() {
        received.clear()
        frameId = -1
        chunkSize = 0
        lastIndex = -1
        endOffset = -1
    }

    /**
     * Feed one datagram. Returns a completed frame, or null if more chunks are needed
     * (or the datagram was discarded). The returned [Frame.data] buffer is reused by the
     * assembler, so consumers must copy it before the next call.
     */
    fun offer(datagram: ByteArray, length: Int): Frame? {
        val payload = length - TubeProtocol.FRAME_HEADER_LENGTH
        if (payload <= 0) return null

        val id = datagram[0].toInt() and 0xFF
        val lastFlag = datagram[1].toInt() and 0xFF
        val index = datagram[2].toInt() and 0xFF
        if (index < 1) return null

        if (index == 1) {
            // A new frame begins. Anything half-built is lost.
            if (frameId >= 0) framesDropped++
            reset()
            frameId = id
            chunkSize = payload
        } else if (frameId < 0 || id != frameId) {
            // Chunk of a frame whose start we never saw, or of a different frame: drop.
            if (frameId >= 0) {
                framesDropped++
                reset()
            }
            return null
        }

        if (chunkSize <= 0) return null
        val offset = (index - 1) * chunkSize
        if (offset + payload > frame.size) {
            framesDropped++
            reset()
            return null
        }
        System.arraycopy(datagram, TubeProtocol.FRAME_HEADER_LENGTH, frame, offset, payload)
        received.set(index - 1)

        if (lastFlag != 0) {
            lastIndex = index
            angle = decodeAngle(lastFlag, datagram[3].toInt() and 0xFF)
            endOffset = offset + payload
            if (!endsWithEoi(frame, endOffset)) {
                // Some firmware truncates the EOI marker on a full-size last chunk; patch it in.
                if (endOffset + 2 <= frame.size) {
                    frame[endOffset] = 0xFF.toByte()
                    frame[endOffset + 1] = 0xD9.toByte()
                    endOffset += 2
                }
            }
        }

        if (lastIndex > 0) {
            for (i in 0 until lastIndex) {
                if (!received.get(i)) {
                    // Chunks can arrive out of order; keep waiting until the next frame starts.
                    return null
                }
            }
            if (!startsWithSoi(frame)) {
                framesDropped++
                reset()
                return null
            }
            val out = Frame(frame, endOffset, angle, frameId)
            framesCompleted++
            reset()
            return out
        }
        return null
    }

    private fun decodeAngle(lastFlag: Int, low: Int): Float =
        if (floatAngle) {
            val raw = if (lastFlag != 15) low + (lastFlag shl 8) else low
            raw / 10f
        } else {
            (low + if (lastFlag == 2) 256 else 0).toFloat()
        }

    private fun startsWithSoi(buf: ByteArray) =
        buf[0] == 0xFF.toByte() && buf[1] == 0xD8.toByte()

    private fun endsWithEoi(buf: ByteArray, end: Int) =
        end >= 2 && buf[end - 2] == 0xFF.toByte() && buf[end - 1] == 0xD9.toByte()
}
