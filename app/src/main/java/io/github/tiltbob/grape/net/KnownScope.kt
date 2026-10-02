package io.github.tiltbob.grape.net

import io.github.tiltbob.grape.camera.DeviceInfo

/**
 * A scope the app has joined before, remembered so the next time is quicker:
 *  - it is listed as soon as the app opens, before any radio has heard it, so a tap can
 *    ask Android for its Wi-Fi straight away (Android does its own quick scan for the
 *    SSID and BSSID, and skips the approval dialog it already remembers);
 *  - a Bluetooth-only sighting gets the BSSID and security type from here;
 *  - the camera's address and board info let discovery go straight to it.
 */
data class KnownScope(
    val ssid: String,
    val bssid: String? = null,
    val bleAddress: String? = null,
    /** Wi-Fi scan capabilities string from the last time Wi-Fi saw it (security type). */
    val capabilities: String? = null,
    /** What the camera said about itself on that network, the last time we found it. */
    val device: DeviceInfo? = null,
    val lastJoinedMs: Long = 0L,
) {
    /** Fold in a fresh sighting; details from the air win over remembered ones. */
    fun withSighting(camera: NearbyCamera): KnownScope = copy(
        bssid = camera.bssid ?: bssid,
        bleAddress = camera.bleAddress ?: bleAddress,
        capabilities = camera.capabilities ?: capabilities,
    )

    /** What to show in the nearby list when no radio has heard this scope (yet). */
    fun asNearby(): NearbyCamera = NearbyCamera(
        ssid = ssid,
        bssid = bssid,
        bleAddress = bleAddress,
        capabilities = capabilities,
        remembered = true,
        lastJoinedMs = lastJoinedMs,
    )

    /** A sighting of this scope, completed with whatever the radios did not report. */
    fun complete(camera: NearbyCamera): NearbyCamera = camera.copy(
        bssid = camera.bssid ?: bssid,
        bleAddress = camera.bleAddress ?: bleAddress,
        capabilities = camera.capabilities ?: capabilities,
        remembered = true,
        lastJoinedMs = lastJoinedMs,
    )

    companion object {
        /** How many scopes to keep; the least recently joined fall off the end. */
        const val MAX = 8

        /** Record a join of [camera] at [nowMs] in [scopes], most recently joined first. */
        fun remember(scopes: List<KnownScope>, camera: NearbyCamera, nowMs: Long): List<KnownScope> {
            val existing = scopes.firstOrNull { it.ssid.equals(camera.ssid, ignoreCase = true) }
            val updated = (existing ?: KnownScope(ssid = camera.ssid))
                .withSighting(camera)
                .copy(lastJoinedMs = nowMs)
            return listOf(updated) + scopes.filterNot { it.ssid.equals(camera.ssid, ignoreCase = true) }
                .sortedByDescending { it.lastJoinedMs }
                .take(MAX - 1)
        }

        /** Attach what the camera on [ssid]'s network reported about itself. */
        fun learn(scopes: List<KnownScope>, ssid: String, device: DeviceInfo): List<KnownScope> =
            scopes.map { if (it.ssid.equals(ssid, ignoreCase = true)) it.copy(device = device) else it }

        fun forget(scopes: List<KnownScope>, ssid: String): List<KnownScope> =
            scopes.filterNot { it.ssid.equals(ssid, ignoreCase = true) }

        /**
         * The list to show: everything the radios hear now (strongest first), completed from
         * memory, then remembered scopes nobody has heard yet (most recently used first).
         */
        fun listing(heard: List<NearbyCamera>, scopes: List<KnownScope>): List<NearbyCamera> {
            val bySsid = scopes.associateBy { it.ssid.lowercase() }
            val live = heard.map { cam -> bySsid[cam.ssid.lowercase()]?.complete(cam) ?: cam }
            val heardSsids = heard.map { it.ssid.lowercase() }.toSet()
            val silent = scopes
                .filter { it.ssid.lowercase() !in heardSsids }
                .sortedByDescending { it.lastJoinedMs }
                .map { it.asNearby() }
            return live + silent
        }
    }
}
