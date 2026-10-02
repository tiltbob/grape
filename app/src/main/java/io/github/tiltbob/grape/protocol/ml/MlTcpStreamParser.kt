package io.github.tiltbob.grape.protocol.ml

/**
 * Splits the TCP video stream (port 7060) into frames.
 *
 * The stream is `BoundaryS` + 32-byte header + payload + `BoundaryE`, repeated. The parser
 * is a byte state machine exactly like the vendor library's: it hunts for the marker text,
 * accumulates everything after `BoundaryS`, and emits when `BoundaryE` closes the frame.
 * The payload's middle byte is inverted on the wire and restored here.
 */
class MlTcpStreamParser(
    private val onFrame: (jpeg: ByteArray, header: MlProtocol.TcpFrameHeader) -> Unit,
    maxFrameLength: Int = MlProtocol.MAX_FRAME_LENGTH,
) {
    private val buffer = ByteArray(maxFrameLength + MlProtocol.TCP_FRAME_HEADER_LENGTH + 16)
    private var length = 0
    private var state = 0
    private var accumulating = false

    var framesCompleted = 0L
        private set
    var framesRejected = 0L
        private set

    fun reset() {
        length = 0
        state = 0
        accumulating = false
    }

    fun feed(data: ByteArray, count: Int) {
        for (i in 0 until count) {
            val b = data[i]
            if (accumulating) {
                if (length < buffer.size) buffer[length++] = b else reset()
            }
            val c = b.toInt() and 0xFF
            if (state < MARKER_PREFIX.size) {
                state = if (c == MARKER_PREFIX[state]) state + 1 else if (c == MARKER_PREFIX[0]) 1 else 0
            } else {
                when (c) {
                    'S'.code -> {
                        length = 0
                        accumulating = true
                        state = 0
                    }
                    'E'.code -> {
                        if (accumulating) complete()
                        accumulating = false
                        state = 0
                    }
                    else -> state = MARKER_PREFIX.size
                }
            }
        }
    }

    private fun complete() {
        val content = length - MlProtocol.TCP_END.size
        if (content < MlProtocol.TCP_FRAME_HEADER_LENGTH) return
        val header = MlProtocol.parseTcpFrameHeader(buffer, 0, content) ?: return
        val payload = content - MlProtocol.TCP_FRAME_HEADER_LENGTH
        if (header.type != 0 || header.payloadLength != payload || payload <= 2) {
            framesRejected++
            return
        }
        val start = MlProtocol.TCP_FRAME_HEADER_LENGTH
        buffer[start + payload / 2] = buffer[start + payload / 2].toInt().inv().toByte()
        if (buffer[start] != 0xFF.toByte() || buffer[start + 1] != 0xD8.toByte()) {
            framesRejected++
            return
        }
        // The camera may append a few bytes after the EOI marker; trim to it.
        var end = start + payload
        val floor = maxOf(start + 2, end - 16)
        var k = end
        while (k - 2 >= floor) {
            if (buffer[k - 2] == 0xFF.toByte() && buffer[k - 1] == 0xD9.toByte()) {
                end = k
                break
            }
            k--
        }
        framesCompleted++
        onFrame(buffer.copyOfRange(start, end), header)
    }

    private companion object {
        val MARKER_PREFIX = "Boundary".map { it.code }
    }
}
