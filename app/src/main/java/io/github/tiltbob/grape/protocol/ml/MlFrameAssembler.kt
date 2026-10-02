package io.github.tiltbob.grape.protocol.ml

/**
 * Reassembles JPEG frames from UDP video chunks ([MlProtocol.VideoChunk]).
 *
 * Chunks of a frame may interleave with the next frame's, so up to [maxInFlight] frames are
 * tracked at once. A frame is delivered when all of its chunks have been seen; the single
 * inverted byte the camera inserts is restored unless the chunk header says the frame is plain.
 */
class MlFrameAssembler(private val maxInFlight: Int = 2) {

    class Frame(val jpeg: ByteArray, val frameId: Int, val angleRaw: Int, val kind: Int)

    private class Pending(val chunk: MlProtocol.VideoChunk) {
        val parts = arrayOfNulls<ByteArray>(chunk.chunkCount)
        var received = 0
    }

    private val pending = LinkedHashMap<Int, Pending>()

    var framesCompleted = 0L
        private set
    var framesDropped = 0L
        private set

    fun reset() = pending.clear()

    /** Feed one datagram's parsed header plus its raw bytes. Returns a frame when complete. */
    fun offer(chunk: MlProtocol.VideoChunk, datagram: ByteArray): Frame? {
        if (chunk.packetType != MlProtocol.VIDEO_PACKET_TYPE) return null
        if (chunk.chunkCount <= 0 || chunk.chunkIndex !in 1..chunk.chunkCount) return null
        if (chunk.frameLength <= 0 || chunk.frameLength > MlProtocol.MAX_FRAME_LENGTH) return null

        val p = pending.getOrPut(chunk.frameId) {
            while (pending.size >= maxInFlight) {
                val oldest = pending.keys.first()
                pending.remove(oldest)
                framesDropped++
            }
            Pending(chunk)
        }
        val slot = chunk.chunkIndex - 1
        if (p.parts[slot] != null) return null // duplicate
        p.parts[slot] = datagram.copyOfRange(
            MlProtocol.VIDEO_CHUNK_HEADER_LENGTH,
            MlProtocol.VIDEO_CHUNK_HEADER_LENGTH + chunk.chunkLength,
        )
        p.received++
        if (p.received < chunk.chunkCount) return null

        pending.remove(chunk.frameId)
        val total = p.parts.sumOf { it!!.size }
        val length = minOf(total, p.chunk.frameLength)
        val jpeg = ByteArray(length)
        var off = 0
        for (part in p.parts) {
            val n = minOf(part!!.size, length - off)
            if (n <= 0) break
            System.arraycopy(part, 0, jpeg, off, n)
            off += n
        }
        if (!p.chunk.plain) {
            val i = MlProtocol.obfuscatedIndex(p.chunk.key, p.chunk.frameLength)
            if (i in jpeg.indices) jpeg[i] = jpeg[i].toInt().inv().toByte()
        }
        framesCompleted++
        return Frame(jpeg, p.chunk.frameId, p.chunk.angleRaw, p.chunk.frameKind)
    }
}
