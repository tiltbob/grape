package io.github.tiltbob.grape.protocol.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MlFrameAssemblerTest {

    private fun jpeg(size: Int) = ByteArray(size) { (it * 7 % 251).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
    }

    /** Split like the camera: fixed chunk size, last chunk shorter, one byte inverted on the wire. */
    private fun datagrams(frameId: Int, data: ByteArray, chunkSize: Int, key: Int, plain: Boolean): List<ByteArray> {
        val wire = data.copyOf()
        if (!plain) {
            val i = MlProtocol.obfuscatedIndex(key, data.size)
            wire[i] = wire[i].toInt().inv().toByte()
        }
        val count = (wire.size + chunkSize - 1) / chunkSize
        return (0 until count).map { i ->
            val part = wire.copyOfRange(i * chunkSize, minOf(wire.size, (i + 1) * chunkSize))
            MlProtocolTest.chunkDatagram(frameId, data.size, i + 1, count, part, key, plain)
        }
    }

    private fun feed(a: MlFrameAssembler, list: List<ByteArray>): MlFrameAssembler.Frame? {
        var out: MlFrameAssembler.Frame? = null
        for (d in list) {
            val c = MlProtocol.parseVideoChunk(d, d.size)!!
            out = a.offer(c, d) ?: out
        }
        return out
    }

    @Test
    fun reassemblesAndDeobfuscates() {
        val data = jpeg(10_000)
        val a = MlFrameAssembler()
        val frame = feed(a, datagrams(1, data, 2900, key = 0x5A5A5A5A, plain = false))
        assertNotNull(frame)
        assertArrayEquals(data, frame!!.jpeg)
        assertEquals(1, a.framesCompleted)
    }

    @Test
    fun plainFramesAreNotTouched() {
        val data = jpeg(4_000)
        val frame = feed(MlFrameAssembler(), datagrams(2, data, 1388, key = 99, plain = true))
        assertArrayEquals(data, frame!!.jpeg)
    }

    @Test
    fun interleavedFramesAndOutOfOrderChunks() {
        val d1 = jpeg(6_000)
        val d2 = jpeg(5_000)
        val l1 = datagrams(10, d1, 2900, key = 1, plain = false)
        val l2 = datagrams(11, d2, 2900, key = 2, plain = false)
        val a = MlFrameAssembler()
        val order = listOf(l1[2], l2[0], l1[0], l2[1], l1[1])
        val frames = mutableListOf<MlFrameAssembler.Frame>()
        for (d in order) {
            val c = MlProtocol.parseVideoChunk(d, d.size)!!
            a.offer(c, d)?.let { frames += it }
        }
        assertEquals(2, frames.size)
        assertEquals(11, frames[0].frameId)
        assertArrayEquals(d2, frames[0].jpeg)
        assertEquals(10, frames[1].frameId)
        assertArrayEquals(d1, frames[1].jpeg)
    }

    @Test
    fun dropsStaleFrames() {
        val a = MlFrameAssembler(maxInFlight = 2)
        val lists = (1..3).map { datagrams(it, jpeg(6_000), 2900, key = it, plain = true) }
        // first chunk of three different frames: the oldest is evicted
        for (l in lists) {
            val d = l[0]
            assertNull(a.offer(MlProtocol.parseVideoChunk(d, d.size)!!, d))
        }
        assertEquals(1, a.framesDropped)
    }
}
