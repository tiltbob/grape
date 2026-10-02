package io.github.tiltbob.grape

import android.app.Application
import io.github.tiltbob.grape.net.NetworkLink

class GrapeApp : Application() {
    /** The Wi-Fi network the camera lives on; shared by the connect and viewer screens. */
    lateinit var link: NetworkLink
        private set

    override fun onCreate() {
        super.onCreate()
        link = NetworkLink(this)
    }
}
