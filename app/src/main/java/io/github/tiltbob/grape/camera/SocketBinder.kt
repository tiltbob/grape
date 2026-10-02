package io.github.tiltbob.grape.camera

import java.net.DatagramSocket
import java.net.Socket

/**
 * Hooks invoked on every socket before it is bound or connected, so the app can pin all
 * camera traffic to the camera's Wi-Fi network (Android `Network.bindSocket`).
 */
class SocketBinder(
    val datagram: (DatagramSocket) -> Unit = {},
    val stream: (Socket) -> Unit = {},
) {
    companion object {
        val NONE = SocketBinder()
    }
}
