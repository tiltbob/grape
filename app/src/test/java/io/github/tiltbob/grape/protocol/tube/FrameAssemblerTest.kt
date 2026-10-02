package io.github.tiltbob.grape.protocol.tube

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameAssemblerTest {

    private fun fakeJpeg(size: Int): ByteArray {
        val jpeg = ByteArray(size) { (it % 251).toByte() }
        jpeg[0] = 0xFF.toByte(); jpeg[1] = 0xD8.toByte()
        jpeg[size - 2] = 0xFF.toByte(); jpeg[size - 1] = 0xD9.toByte()
        return jpeg
    }

    /** Split a JPEG into datagrams exactly as the camera does. */
    private fun chunk(jpeg: ByteArray, chunkSize: Int, frameId: Int, lastFlag: Int, angleLow: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var offset = 0
        var index = 1
        while (offset < jpeg.size) {
            val len = minOf(chunkSize, jpeg.size - offset)
            val last = offset + len >= jpeg.size
            val d = ByteArray(4 + len)
            d[0] = frameId.toByte()
            d[1] = if (last) lastFlag.toByte() else 0
            d[2] = index.toByte()
            d[3] = if (last) angleLow.toByte() else 0
            System.arraycopy(jpeg, offset, d, 4, len)
            out += d
            offset += len
            index++
        }
        return out
    }

    @Test
    fun reassemblesInOrder() {
        val jpeg = fakeJpeg(10_000)
        val a = FrameAssembler()
        val datagrams = chunk(jpeg, 1400, frameId = 7, lastFlag = 1, angleLow = 90)
        var frame: FrameAssembler.Frame? = null
        for (d in datagrams) {
            assertNull(frame)
            frame = a.offer(d, d.size)
        }
        assertNotNull(frame)
        assertArrayEquals(jpeg, frame!!.toByteArray())
        assertEquals(90f, frame.angleDegrees)
        assertEquals(7, frame.frameId)
        assertEquals(1, a.framesCompleted)
        assertEquals(0, a.framesDropped)
    }

    @Test
    fun reassemblesOutOfOrder() {
        val jpeg = fakeJpeg(6_000)
        val a = FrameAssembler()
        val datagrams = chunk(jpeg, 1000, frameId = 1, lastFlag = 1, angleLow = 10)
        // first chunk must come first (it fixes the chunk size); shuffle the rest
        val order = listOf(datagrams[0], datagrams[5], datagrams[2], datagrams[1], datagrams[4], datagrams[3])
        var frame: FrameAssembler.Frame? = null
        for (d in order) frame = a.offer(d, d.size) ?: frame
        assertNotNull(frame)
        assertArrayEquals(jpeg, frame!!.toByteArray())
    }

    @Test
    fun angleHighBitInIntegerMode() {
        val jpeg = fakeJpeg(3_000)
        val a = FrameAssembler(floatAngle = false)
        val datagrams = chunk(jpeg, 1000, frameId = 3, lastFlag = 2, angleLow = 44)
        var frame: FrameAssembler.Frame? = null
        for (d in datagrams) frame = a.offer(d, d.size) ?: frame
        assertEquals(300f, frame!!.angleDegrees)
    }

    @Test
    fun angleInFloatMode() {
        val jpeg = fakeJpeg(3_000)
        val a = FrameAssembler(floatAngle = true)
        // lastFlag carries the high byte: (0x0C << 8 | 0x4D) / 10 = 3149 / 10
        val datagrams = chunk(jpeg, 1000, frameId = 3, lastFlag = 0x0C, angleLow = 0x4D)
        var frame: FrameAssembler.Frame? = null
        for (d in datagrams) frame = a.offer(d, d.size) ?: frame
        assertEquals(314.9f, frame!!.angleDegrees, 0.001f)

        // lastFlag 15 means "no high byte"
        val a2 = FrameAssembler(floatAngle = true)
        val datagrams2 = chunk(jpeg, 1000, frameId = 4, lastFlag = 15, angleLow = 123)
        frame = null
        for (d in datagrams2) frame = a2.offer(d, d.size) ?: frame
        assertEquals(12.3f, frame!!.angleDegrees, 0.001f)
    }

    @Test
    fun lostChunkDropsFrameAndRecovers() {
        val a = FrameAssembler()
        val jpeg1 = fakeJpeg(5_000)
        val d1 = chunk(jpeg1, 1000, frameId = 1, lastFlag = 1, angleLow = 0).toMutableList()
        d1.removeAt(2) // lose chunk 3
        for (d in d1) assertNull(a.offer(d, d.size))

        val jpeg2 = fakeJpeg(4_000)
        var frame: FrameAssembler.Frame? = null
        for (d in chunk(jpeg2, 1000, frameId = 2, lastFlag = 1, angleLow = 5)) frame = a.offer(d, d.size) ?: frame
        assertNotNull(frame)
        assertArrayEquals(jpeg2, frame!!.toByteArray())
        assertEquals(1, a.framesDropped)
        assertEquals(1, a.framesCompleted)
    }

    @Test
    fun patchesMissingEoiMarker() {
        val a = FrameAssembler()
        val jpeg = fakeJpeg(2_000)
        val truncated = jpeg.copyOf(jpeg.size - 2) // camera cut the FF D9
        var frame: FrameAssembler.Frame? = null
        for (d in chunk(truncated, 1000, frameId = 9, lastFlag = 1, angleLow = 0)) frame = a.offer(d, d.size) ?: frame
        val out = frame!!.toByteArray()
        assertEquals(jpeg.size, out.size)
        assertArrayEquals(jpeg, out)
    }

    @Test
    fun ignoresTinyAndForeignDatagrams() {
        val a = FrameAssembler()
        assertNull(a.offer(byteArrayOf(1, 0, 1, 0), 4)) // header only
        val foreign = byteArrayOf(5, 1, 2, 0, 1, 2, 3)   // chunk 2 of a frame we never started
        assertNull(a.offer(foreign, foreign.size))
        assertEquals(0, a.framesDropped)
    }
}
