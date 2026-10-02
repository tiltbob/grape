package io.github.tiltbob.grape.camera

import io.github.tiltbob.grape.protocol.ml.MlCameraClient
import io.github.tiltbob.grape.protocol.tube.TubeCameraClient

/** Picks the client implementation for a device. */
object CameraClients {
    fun create(info: DeviceInfo, binder: SocketBinder): CameraClient = when (info.protocol) {
        CameraProtocol.TUBE -> TubeCameraClient(info, binder)
        CameraProtocol.ML -> MlCameraClient(info, binder)
    }
}
