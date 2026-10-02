package io.github.tiltbob.grape.discovery

import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.SocketBinder
import io.github.tiltbob.grape.debug.DebugLog
import io.github.tiltbob.grape.protocol.ml.MlDiscovery
import io.github.tiltbob.grape.protocol.tube.TubeDiscovery
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Runs every protocol's discovery concurrently. The scope's own network has exactly one
 * camera on it, so the first discovery to find anything ends the scan; the rest are
 * cancelled rather than waited out.
 */
object CameraDiscovery {
    suspend fun scan(
        binder: SocketBinder,
        extraHosts: List<String> = emptyList(),
        timeoutMs: Long = 2500,
    ): List<DeviceInfo> = coroutineScope {
        val started = System.currentTimeMillis()
        DebugLog.log("Discovery", "scan: extraHosts=$extraHosts timeout=${timeoutMs}ms")
        val pending = mutableListOf<Deferred<List<DeviceInfo>>>(
            async { TubeDiscovery.probe(binder, extraHosts, timeoutMs, stopAtFirst = true) },
            async { MlDiscovery.probe(binder, extraHosts) },
            async { TubeDiscovery.listenForBeacons(binder, timeoutMs) },
        )
        var result: List<DeviceInfo> = emptyList()
        while (pending.isNotEmpty() && result.isEmpty()) {
            val (done, found) = select {
                for (d in pending) d.onAwait { d to it }
            }
            pending.remove(done)
            result = found
        }
        pending.forEach { it.cancel() }
        DebugLog.log("Discovery", "scan: ${result.size} camera(s) after ${System.currentTimeMillis() - started}ms: ${result.map { "${it.host}/${it.protocol}/${it.model}" }}")
        result
    }
}
