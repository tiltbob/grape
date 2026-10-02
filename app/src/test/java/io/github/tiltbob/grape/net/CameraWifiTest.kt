package io.github.tiltbob.grape.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWifiTest {

    @Test
    fun recognisesCameraNames() {
        assertTrue(CameraWifi.looksLikeCamera("bebird-ES-1A2B3C"))
        assertTrue(CameraWifi.looksLikeCamera("Bebird M9 Pro"))
        assertTrue(CameraWifi.looksLikeCamera("XLife-123"))
        assertFalse(CameraWifi.looksLikeCamera("HomeWifi"))
        assertFalse(CameraWifi.looksLikeCamera(null))
        assertTrue(CameraWifi.looksLikeCamera("Note3-1234", listOf("note3")))
    }

    @Test
    fun bssidIsBleAddressMinusTwo() {
        assertEquals("AA:BB:CC:DD:EE:FD", CameraWifi.bssidFromBleAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:00", CameraWifi.bssidFromBleAddress("AA:BB:CC:DD:EE:01")) // saturates
        assertNull(CameraWifi.bssidFromBleAddress("not a mac"))
        assertNull(CameraWifi.bssidFromBleAddress("AA:BB:CC:DD:EE"))
    }

    @Test
    fun securedModelsNeedThePassphrase() {
        assertTrue(CameraWifi.isSecuredBySsid("bebird-W3-1234"))
        assertTrue(CameraWifi.isSecuredBySsid("bebird_E3_0001"))
        assertTrue(CameraWifi.isSecuredBySsid("bebird-Elite14W-77"))
        assertTrue(CameraWifi.isSecuredBySsid("bebird-Max 22-1"))
        assertFalse(CameraWifi.isSecuredBySsid("bebird-ES-1A2B3C"))
        assertFalse(CameraWifi.isSecuredBySsid("bebird"))
        assertFalse(CameraWifi.isSecuredBySsid(null))
    }

    @Test
    fun capabilitiesWinOverSsid() {
        assertEquals(CameraWifi.Security.WPA3, CameraWifi.securityFor("bebird-ES-1", "[WPA3-SAE-CCMP][ESS]"))
        assertEquals(CameraWifi.Security.WPA2, CameraWifi.securityFor("bebird-ES-1", "[WPA2-PSK-CCMP][ESS]"))
        assertEquals(CameraWifi.Security.OPEN, CameraWifi.securityFor("bebird-W3-1", "[ESS]"))
        assertEquals(CameraWifi.Security.WPA2, CameraWifi.securityFor("bebird-W3-1", null))
        assertEquals(CameraWifi.Security.OPEN, CameraWifi.securityFor("bebird-ES-1", null))
    }

    @Test
    fun mergeKeepsBestOfBothRadios() {
        val ble = NearbyCamera(ssid = "bebird-ES-1", bssid = "AA:BB:CC:DD:EE:FD", bleAddress = "AA:BB:CC:DD:EE:FF", seenByBluetooth = true, rssi = -60)
        val wifi = NearbyCamera(ssid = "bebird-ES-1", bssid = "aa:bb:cc:dd:ee:fd", capabilities = "[ESS]", seenByWifi = true, rssi = -50)
        val m = ble.merge(wifi)
        assertTrue(m.seenByBluetooth && m.seenByWifi)
        assertEquals("AA:BB:CC:DD:EE:FF", m.bleAddress)
        assertEquals("[ESS]", m.capabilities)
        assertEquals(-50, m.rssi)
    }
}
