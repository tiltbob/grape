package io.github.tiltbob.grape.protocol.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MlProtocolTest {

    @Test
    fun requestLayoutMatchesNativeLibrary() {
        val r = MlProtocol.request(MlProtocol.CMD_SET_LIGHT, sequence = 7, parameter = 55)
        assertEquals(24, r.size)
        // 99 99 | 16 10 | 07 00 00 00 | 37 00 00 00 | zeros
        assertArrayEquals(
            byteArrayOf(
                0x99.toByte(), 0x99.toByte(), 0x16, 0x10,
                7, 0, 0, 0,
                55, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            r,
        )
        val start = MlProtocol.videoControl(MlProtocol.VIDEO_START)
        assertArrayEquals(byteArrayOf(0x99.toByte(), 0x99.toByte(), 1, 0) + ByteArray(20), start)
    }

    @Test
    fun replyMatching() {
        val reply = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x9999.toShort()).putShort(MlProtocol.CMD_GET_LIGHT.toShort()).putInt(0).putInt(42).array()
        assertTrue(MlProtocol.replyMatches(reply, reply.size, MlProtocol.CMD_GET_LIGHT))
        assertFalse(MlProtocol.replyMatches(reply, reply.size, MlProtocol.CMD_GET_BATTERY))
        assertEquals(42, MlProtocol.replyInt(reply, reply.size))
        assertNull(MlProtocol.replyInt(reply, 8))
    }

    @Test
    fun batteryDecoding() {
        val reply = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x9999.toShort()).putShort(MlProtocol.CMD_GET_BATTERY.toShort()).putInt(1)
            .putInt(0x10000 or 3900) // charging, 3900 mV
            .putInt(0)
            .putInt(0x10000 or 77)   // direct percent present
            .array()
        val b = MlProtocol.parseBattery(reply, reply.size)!!
        assertTrue(b.charging)
        assertEquals(3900, b.millivolts)
        assertEquals(77, b.percentDirect)
        assertEquals(83, b.percentEstimate(null))        // (3900-3400)/6
        assertEquals(66, b.percentEstimate("Note3"))     // (3900-3700)/3

        val noDirect = reply.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 5) }
        assertNull(MlProtocol.parseBattery(noDirect, noDirect.size)!!.percentDirect)
    }

    @Test
    fun boardInfoChecksum() {
        val json = "{\"model\":\"M9 Pro\",\"brand\":\"bebird\"}"
        val reply = ByteArray(24 + 1024)
        val bb = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, 0x9999.toShort())
        bb.putShort(2, MlProtocol.CMD_GET_BOARD_INFO.toShort())
        System.arraycopy(json.toByteArray(), 0, reply, 24, json.length)
        var x = 0
        for (i in 24 until 24 + 1024) x = x xor (reply[i].toInt() and 0xFF)
        bb.putInt(8, x)
        bb.putInt(12, json.length)
        assertEquals(json, MlProtocol.parseBoardInfo(reply, reply.size))

        bb.putInt(8, x xor 1)
        assertNull(MlProtocol.parseBoardInfo(reply, reply.size))
    }

    @Test
    fun obfuscatedIndexMatchesFormula() {
        // ((len ^ key) + len + (~len & 1)) ^ len, modulo len
        assertEquals(1, MlProtocol.obfuscatedIndex(0, 10))
        assertEquals(0, MlProtocol.obfuscatedIndex(123, 0))
        val key = 0x12345678
        val len = 48213
        val u = ((len xor key) + len + (len.inv() and 1)) xor len
        assertEquals(Integer.remainderUnsigned(u, len), MlProtocol.obfuscatedIndex(key, len))
        assertTrue(MlProtocol.obfuscatedIndex(-5, 1000) in 0 until 1000)
    }

    @Test
    fun videoChunkHeaderParses() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val d = chunkDatagram(frameId = 9, frameLength = 5, index = 1, count = 1, payload = payload, key = 77, plain = false, angleRaw = 0xC0000000.toInt() + 90_000)
        val c = MlProtocol.parseVideoChunk(d, d.size)!!
        assertEquals(3, c.packetType)
        assertEquals(9, c.frameId)
        assertEquals(5, c.frameLength)
        assertEquals(1, c.chunkIndex)
        assertEquals(1, c.chunkCount)
        assertEquals(5, c.chunkLength)
        assertEquals(77, c.key)
        assertFalse(c.plain)
        assertNull(MlProtocol.parseVideoChunk(d, 20))
    }

    @Test
    fun angleDecoderAbsoluteAndNone() {
        val dec = MlProtocol.AngleDecoder()
        assertNull(dec.decode(0))
        assertNull(dec.decode(0x10001))
        assertEquals(90f, dec.decode(0x80000000.toInt() or 90_000)!!, 0.001f)
        assertEquals(123.456f, dec.decode(0xC0000000.toInt() or 123_456)!!, 0.001f)
    }

    @Test
    fun angleDecoderAccelerometer() {
        val dec = MlProtocol.AngleDecoder()
        // x = 300, y = 300 (both positive, big enough) -> 45 degrees once it has settled
        val raw = (300 shl 10) or 300
        dec.decode(raw)
        val a = dec.decode(raw)!!
        assertEquals(45f, a, 0.5f)
    }

    companion object {
        fun chunkDatagram(
            frameId: Int,
            frameLength: Int,
            index: Int,
            count: Int,
            payload: ByteArray,
            key: Int,
            plain: Boolean,
            angleRaw: Int = 0,
            kind: Int = 0,
        ): ByteArray {
            val d = ByteArray(MlProtocol.VIDEO_CHUNK_HEADER_LENGTH + payload.size)
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0, 0x9999.toShort())
            b.putShort(2, 3)
            b.putInt(8, angleRaw)
            b.putInt(16, if (plain) 1 else 0)
            d[24] = kind.toByte()
            b.putInt(25, frameId)
            b.putInt(29, frameLength)
            b.putShort(33, index.toShort())
            b.putShort(35, count.toShort())
            b.putShort(37, payload.size.toShort())
            b.putInt(39, 1234)
            b.putInt(47, key)
            System.arraycopy(payload, 0, d, MlProtocol.VIDEO_CHUNK_HEADER_LENGTH, payload.size)
            return d
        }
    }
}
