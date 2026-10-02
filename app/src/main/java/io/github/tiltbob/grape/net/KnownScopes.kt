package io.github.tiltbob.grape.net

import android.content.SharedPreferences
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.DeviceInfoJson
import io.github.tiltbob.grape.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scopes this phone has joined, kept in the app's private preferences. Nothing in
 * here ever leaves the phone; it is a few network names and addresses, the same ones the
 * debug log shows.
 */
class KnownScopes(private val prefs: SharedPreferences) {

    private val _scopes = MutableStateFlow(load())
    val scopes: StateFlow<List<KnownScope>> = _scopes.asStateFlow()

    /** The scope joined most recently, which the app rejoins by itself when it hears it. */
    val lastUsed: KnownScope? get() = _scopes.value.maxByOrNull { it.lastJoinedMs }

    fun forSsid(ssid: String?): KnownScope? =
        ssid?.let { s -> _scopes.value.firstOrNull { it.ssid.equals(s, ignoreCase = true) } }

    fun remember(camera: NearbyCamera) = update(KnownScope.remember(_scopes.value, camera, System.currentTimeMillis()))

    fun learn(ssid: String, device: DeviceInfo) = update(KnownScope.learn(_scopes.value, ssid, device))

    fun forget(ssid: String) {
        DebugLog.log(TAG, "forget $ssid")
        update(KnownScope.forget(_scopes.value, ssid))
    }

    private fun update(list: List<KnownScope>) {
        _scopes.value = list
        prefs.edit().putString(KEY, encode(list)).apply()
    }

    private fun load(): List<KnownScope> {
        val text = prefs.getString(KEY, null)
        val list = if (text != null) decode(text) else emptyList()
        // One-time migration from the single "last SSID" the app used to keep.
        val legacy = prefs.getString(LEGACY_LAST_SSID, null)
        if (legacy != null) {
            prefs.edit().remove(LEGACY_LAST_SSID).apply()
            if (list.none { it.ssid.equals(legacy, ignoreCase = true) }) {
                val migrated = KnownScope.remember(list, NearbyCamera(ssid = legacy), System.currentTimeMillis())
                prefs.edit().putString(KEY, encode(migrated)).apply()
                return migrated
            }
        }
        return list
    }

    private fun encode(list: List<KnownScope>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("ssid", s.ssid)
                    put("bssid", s.bssid)
                    put("ble", s.bleAddress)
                    put("caps", s.capabilities)
                    put("device", s.device?.let { DeviceInfoJson.encode(it) })
                    put("joined", s.lastJoinedMs)
                },
            )
        }
        return arr.toString()
    }

    private fun decode(text: String): List<KnownScope> = runCatching {
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ssid = o.optString("ssid")
                if (ssid.isBlank()) continue
                add(
                    KnownScope(
                        ssid = ssid,
                        bssid = o.optString("bssid").ifBlank { null },
                        bleAddress = o.optString("ble").ifBlank { null },
                        capabilities = o.optString("caps").ifBlank { null },
                        device = o.optString("device").ifBlank { null }?.let { d -> runCatching { DeviceInfoJson.decode(d) }.getOrNull() },
                        lastJoinedMs = o.optLong("joined", 0L),
                    ),
                )
            }
        }
    }.getOrElse {
        DebugLog.log(TAG, "could not read remembered scopes, starting over", it)
        emptyList()
    }

    private companion object {
        const val TAG = "Known"
        const val KEY = "known_scopes"
        const val LEGACY_LAST_SSID = "last_camera_ssid"
    }
}
