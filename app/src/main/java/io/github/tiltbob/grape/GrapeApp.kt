package io.github.tiltbob.grape

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import io.github.tiltbob.grape.debug.CrashRecord
import io.github.tiltbob.grape.debug.DebugLog
import io.github.tiltbob.grape.net.KnownScopes
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

    /** Scopes joined before, listed before any scan and rejoined without a tap. */
    lateinit var known: KnownScopes
        private set

    override fun onCreate() {
        super.onCreate()
        DebugLog.sink = { tag, message -> Log.d("EarDigger/$tag", message) }
        DebugLog.log("App", "start: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        CrashRecord.install(this)
        link = NetworkLink(this)
        scanner = NearbyScanner(this)
        prefs = getSharedPreferences("grape", Context.MODE_PRIVATE)
        known = KnownScopes(prefs)
        DebugLog.log("App", "remembered scopes: ${known.scopes.value.map { it.ssid }}")
    }
}
