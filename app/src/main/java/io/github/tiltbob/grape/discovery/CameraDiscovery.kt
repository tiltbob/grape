package io.github.tiltbob.grape.discovery

import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.SocketBinder
import io.github.tiltbob.grape.debug.DebugLog
import io.github.tiltbob.grape.protocol.ml.MlDiscovery
import io.github.tiltbob.grape.protocol.tube.TubeDiscovery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Runs every protocol's discovery concurrently and merges the results by host. */
object CameraDiscovery {
    suspend fun scan(
        binder: SocketBinder,
        extraHosts: List<String> = emptyList(),
        timeoutMs: Long = 2500,
    ): List<DeviceInfo> = coroutineScope {
        val started = System.currentTimeMillis()
        DebugLog.log("Discovery", "scan: extraHosts=$extraHosts timeout=${timeoutMs}ms")
        val probe = async { TubeDiscovery.probe(binder, extraHosts, timeoutMs) }
        val beacons = async { TubeDiscovery.listenForBeacons(binder, timeoutMs) }
        val ml = async { MlDiscovery.probe(binder, extraHosts) }
        val merged = linkedMapOf<String, DeviceInfo>()
        // Board-info replies carry more detail than beacons, so they win on conflict.
        for (d in beacons.await()) merged[d.host] = d
        for (d in probe.await()) merged[d.host] = d
        for (d in ml.await()) merged.putIfAbsent(d.host, d)
        DebugLog.log("Discovery", "scan: ${merged.size} camera(s) after ${System.currentTimeMillis() - started}ms: ${merged.values.map { "${it.host}/${it.protocol}/${it.model}" }}")
        merged.values.toList()
    }
}
