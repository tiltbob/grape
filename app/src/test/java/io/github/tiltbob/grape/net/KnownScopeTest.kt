package io.github.tiltbob.grape.net

import io.github.tiltbob.grape.camera.CameraProtocol
import io.github.tiltbob.grape.camera.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownScopeTest {

    private val r1 = NearbyCamera(ssid = "bebird-R1-630136", bssid = "AA:BB:CC:00:00:01", capabilities = "[ESS]", seenByWifi = true, rssi = -50)
    private val w3 = NearbyCamera(ssid = "bebird-W3-1234", bleAddress = "AA:BB:CC:00:00:12", seenByBluetooth = true, rssi = -70)

    @Test
    fun rememberPutsTheLatestJoinFirstAndKeepsDetails() {
        var list = KnownScope.remember(emptyList(), r1, nowMs = 1000)
        list = KnownScope.remember(list, w3, nowMs = 2000)
        assertEquals(listOf("bebird-W3-1234", "bebird-R1-630136"), list.map { it.ssid })
        assertEquals("AA:BB:CC:00:00:01", list[1].bssid)
        assertEquals("AA:BB:CC:00:00:12", list[0].bleAddress)

        // Joining R1 again moves it to the front and merges the new sighting into the old one.
        list = KnownScope.remember(list, r1.copy(bssid = null, bleAddress = "AA:BB:CC:00:00:03", seenByBluetooth = true), nowMs = 3000)
        assertEquals("bebird-R1-630136", list[0].ssid)
        assertEquals("AA:BB:CC:00:00:01", list[0].bssid)
        assertEquals("AA:BB:CC:00:00:03", list[0].bleAddress)
        assertEquals(3000L, list[0].lastJoinedMs)
        assertEquals(2, list.size)
    }

    @Test
    fun rememberCapsTheListAtTheLeastRecentlyUsed() {
        var list = emptyList<KnownScope>()
        for (i in 1..(KnownScope.MAX + 3)) {
            list = KnownScope.remember(list, NearbyCamera(ssid = "bebird-$i"), nowMs = i.toLong())
        }
        assertEquals(KnownScope.MAX, list.size)
        assertEquals("bebird-${KnownScope.MAX + 3}", list.first().ssid)
        assertEquals("bebird-4", list.last().ssid)
    }

    @Test
    fun learnAttachesTheCameraToTheRightScope() {
        val device = DeviceInfo(host = "192.168.5.1", protocol = CameraProtocol.TUBE, model = "R1")
        var list = KnownScope.remember(emptyList(), r1, 1)
        list = KnownScope.remember(list, w3, 2)
        list = KnownScope.learn(list, "BEBIRD-r1-630136", device)
        assertEquals(device, list.first { it.ssid == r1.ssid }.device)
        assertNull(list.first { it.ssid == w3.ssid }.device)
    }

    @Test
    fun forgetRemovesOnlyThatScope() {
        var list = KnownScope.remember(emptyList(), r1, 1)
        list = KnownScope.remember(list, w3, 2)
        list = KnownScope.forget(list, "bebird-r1-630136")
        assertEquals(listOf(w3.ssid), list.map { it.ssid })
    }

    @Test
    fun listingShowsHeardScopesFirstThenSilentRememberedOnes() {
        var scopes = KnownScope.remember(emptyList(), r1, 1)
        scopes = KnownScope.remember(scopes, w3, 2)
        // Only W3 is heard right now, and only over Bluetooth (no capabilities from the air).
        val heard = listOf(NearbyCamera(ssid = "bebird-W3-1234", seenByBluetooth = true, rssi = -60))
        val shown = KnownScope.listing(heard, scopes)

        assertEquals(listOf("bebird-W3-1234", "bebird-R1-630136"), shown.map { it.ssid })
        assertTrue(shown[0].heard)
        assertTrue(shown[0].remembered)
        assertEquals("AA:BB:CC:00:00:12", shown[0].bleAddress) // filled in from memory
        assertEquals(-60, shown[0].rssi)
        assertFalse(shown[1].heard)
        assertTrue(shown[1].remembered)
        assertEquals("AA:BB:CC:00:00:01", shown[1].bssid)
        assertEquals(1L, shown[1].lastJoinedMs)
    }

    @Test
    fun listingLeavesUnknownScopesAlone() {
        val stranger = NearbyCamera(ssid = "bebird-ES-9999", seenByWifi = true)
        val shown = KnownScope.listing(listOf(stranger), KnownScope.remember(emptyList(), r1, 1))
        assertEquals(listOf("bebird-ES-9999", "bebird-R1-630136"), shown.map { it.ssid })
        assertFalse(shown[0].remembered)
    }

    @Test
    fun securityComesFromRememberedCapabilitiesWhenOnlyBluetoothHearsIt() {
        val scopes = KnownScope.remember(emptyList(), NearbyCamera(ssid = "bebird-ES-1", capabilities = "[WPA2-PSK-CCMP][ESS]", seenByWifi = true), 1)
        val shown = KnownScope.listing(listOf(NearbyCamera(ssid = "bebird-ES-1", seenByBluetooth = true)), scopes)
        assertEquals(CameraWifi.Security.WPA2, shown[0].security)
    }
}
