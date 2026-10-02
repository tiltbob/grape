package io.github.tiltbob.grape.protocol.tube

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TubeProtocolTest {

    @Test
    fun batteryIsBigEndianStateAndPercent() {
        // state 2 (charging), 65 percent  -> 0x0002_0041
        val b = TubeProtocol.parseBattery(byteArrayOf(0x00, 0x02, 0x00, 0x41))!!
        assertEquals(2, b.state)
        assertEquals(65, b.percent)
        assertTrue(b.charging)
        assertFalse(b.full)
        assertNull(TubeProtocol.parseBattery(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun sensorAngleIsShortIndexNine() {
        val pkt = ByteArray(TubeProtocol.SENSOR_PACKET_LENGTH)
        pkt[18] = 0x01; pkt[19] = 0x2C // 300 big-endian at short index 9
        assertEquals(300, TubeProtocol.parseSensorAngle(pkt))
        pkt[18] = 0x01; pkt[19] = 0x68 // 360 is out of range
        assertNull(TubeProtocol.parseSensorAngle(pkt))
        assertNull(TubeProtocol.parseSensorAngle(ByteArray(10)))
    }

    @Test
    fun commandBytesMatchVendorApp() {
        assertArrayEquals(byteArrayOf(0x20, 0x36), TubeProtocol.START_VIDEO)
        assertArrayEquals(byteArrayOf(0x20, 0x37), TubeProtocol.STOP_VIDEO)
        assertArrayEquals(byteArrayOf(0x66, 0x39, 1, 1), TubeProtocol.GET_BOARD_INFO)
        assertArrayEquals(byteArrayOf(0x66, 0x3A), TubeProtocol.GET_BATTERY)
        assertArrayEquals(byteArrayOf(0x66, 0x3C, 42), TubeProtocol.setBrightness(42))
        assertArrayEquals(byteArrayOf(0x66, 0x3C, 0xFF.toByte()), TubeProtocol.COMMIT_BRIGHTNESS)
        assertArrayEquals(byteArrayOf(0x66, 0x3C, 0xFE.toByte()), TubeProtocol.QUERY_BRIGHTNESS)
        assertArrayEquals(byteArrayOf(0x66, 0x3F, 0, 1), TubeProtocol.triggerLed(0, true))
        assertArrayEquals(byteArrayOf(0x86.toByte(), 0x06, 1), TubeProtocol.toggleAngleStream(true))
    }

    @Test
    fun boardInfoCompletesOnClosingBrace() {
        val part = "{\"model\":\"ES\",".toByteArray()
        assertFalse(TubeProtocol.isBoardInfoComplete(part, part.size))
        val whole = "{\"model\":\"ES\"}".toByteArray()
        assertTrue(TubeProtocol.isBoardInfoComplete(whole, whole.size))
    }
}
