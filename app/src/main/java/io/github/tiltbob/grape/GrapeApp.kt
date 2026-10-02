package io.github.tiltbob.grape

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.github.tiltbob.grape.net.NearbyScanner
import io.github.tiltbob.grape.net.NetworkLink

class GrapeApp : Application() {
    /** The Wi-Fi network the camera lives on; shared by the connect and viewer screens. */
    lateinit var link: NetworkLink
        private set

    /** Bluetooth / Wi-Fi listener for switched-on scopes. */
    lateinit var scanner: NearbyScanner
        private set

    lateinit var prefs: SharedPreferences
        private set

    /** SSID of the scope we last joined, so it can be rejoined without a tap. */
    var lastCameraSsid: String?
        get() = prefs.getString(KEY_LAST_SSID, null)
        set(value) = prefs.edit().putString(KEY_LAST_SSID, value).apply()

    override fun onCreate() {
        super.onCreate()
        link = NetworkLink(this)
        scanner = NearbyScanner(this)
        prefs = getSharedPreferences("grape", Context.MODE_PRIVATE)
    }

    private companion object {
        const val KEY_LAST_SSID = "last_camera_ssid"
    }
}
