package io.github.tiltbob.grape.net

/** A scope seen over the air, before we have joined its Wi-Fi. */
data class NearbyCamera(
    /** The Wi-Fi network name (also the BLE advertised name). */
    val ssid: String,
    /** Access point MAC, from a Wi-Fi scan or derived from the BLE address. */
    val bssid: String? = null,
    val bleAddress: String? = null,
    /** Wi-Fi scan capabilities string, when seen by Wi-Fi. */
    val capabilities: String? = null,
    val seenByBluetooth: Boolean = false,
    val seenByWifi: Boolean = false,
    /** Signal strength in dBm from whichever radio saw it last. */
    val rssi: Int? = null,
) {
    val security: CameraWifi.Security get() = CameraWifi.securityFor(ssid, capabilities)

    fun merge(other: NearbyCamera): NearbyCamera = copy(
        bssid = other.bssid ?: bssid,
        bleAddress = other.bleAddress ?: bleAddress,
        capabilities = other.capabilities ?: capabilities,
        seenByBluetooth = seenByBluetooth || other.seenByBluetooth,
        seenByWifi = seenByWifi || other.seenByWifi,
        rssi = other.rssi ?: rssi,
    )
}
