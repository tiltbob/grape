package io.github.tiltbob.grape.protocol.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MlTcpStreamParserTest {

    private fun jpeg(size: Int) = ByteArray(size) { (it * 13 % 251).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
    }

    private fun frame(data: ByteArray, trailing: Int = 0, angleRaw: Int = 0): ByteArray {
        val payload = data + ByteArray(trailing) { 0x55 }
        val header = ByteArray(MlProtocol.TCP_FRAME_HEADER_LENGTH)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        header[1] = 0
        header[2] = 1
        b.putInt(4, payload.size)
        b.putInt(12, 42)
        b.putInt(20, angleRaw)
        b.putInt(24, 640)
        b.putInt(28, 480)
        val wire = payload.copyOf()
        wire[payload.size / 2] = wire[payload.size / 2].toInt().inv().toByte()
        return MlProtocol.TCP_START + header + wire + MlProtocol.TCP_END
    }

    @Test
    fun parsesFramesAcrossReadBoundaries() {
        val d1 = jpeg(3_000)
        val d2 = jpeg(2_500)
        val stream = ByteArray(17) { 'x'.code.toByte() } + frame(d1, trailing = 3, angleRaw = 7) + frame(d2)
        val got = mutableListOf<Pair<ByteArray, MlProtocol.TcpFrameHeader>>()
        val parser = MlTcpStreamParser({ j, h -> got += j to h })
        var off = 0
        var n = 1
        while (off < stream.size) {
            val len = minOf(n, stream.size - off)
            parser.feed(stream.copyOfRange(off, off + len), len)
            off += len
            n = (n * 3) % 701 + 1
        }
        assertEquals(2, got.size)
        assertArrayEquals(d1, got[0].first)
        assertEquals(7, got[0].second.angleRaw)
        assertEquals(640, got[0].second.width)
        assertArrayEquals(d2, got[1].first)
        assertEquals(2, parser.framesCompleted)
    }

    @Test
    fun rejectsNonVideoAndBadLength() {
        val data = jpeg(1_000)
        val f = frame(data)
        f[MlProtocol.TCP_START.size + 1] = 1 // audio
        var count = 0
        val parser = MlTcpStreamParser({ _, _ -> count++ })
        parser.feed(f, f.size)
        assertEquals(0, count)
        assertEquals(1, parser.framesRejected)
    }
}
