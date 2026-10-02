package io.github.tiltbob.grape.protocol.tube

import io.github.tiltbob.grape.camera.BatteryStatus
import io.github.tiltbob.grape.camera.CameraClient
import io.github.tiltbob.grape.camera.ConnectionState
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.SocketBinder
import io.github.tiltbob.grape.camera.VideoFrame
import io.github.tiltbob.grape.debug.DebugLog
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * [CameraClient] for the UDP "tube" protocol. See [TubeProtocol] for the wire format.
 *
 * @param binder pins every socket to the camera's Wi-Fi network.
 */
class TubeCameraClient(
    override val info: DeviceInfo,
    private val binder: SocketBinder = SocketBinder.NONE,
) : CameraClient {

    private val host: InetAddress = InetAddress.getByName(info.host)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("tube-client"))
    private val commandMutex = Mutex()

    private var commandSocket: DatagramSocket? = null
    private var videoSocket: DatagramSocket? = null
    private var streamJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var lastStartAtMs = 0L

    private val _frames = MutableSharedFlow<VideoFrame>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: SharedFlow<VideoFrame> = _frames.asSharedFlow()

    private val _battery = MutableStateFlow<BatteryStatus?>(null)
    override val battery: StateFlow<BatteryStatus?> = _battery.asStateFlow()

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Frames fully reassembled since [start]. */
    val framesReceived: Long get() = assembler.framesCompleted

    /** Frames lost to missing chunks since [start]. */
    val framesDropped: Long get() = assembler.framesDropped

    private val assembler = FrameAssembler(floatAngle = info.floatAngle)

    /** Angle encoding used by this unit; discovered from board info, may be updated after connect. */
    var floatAngle: Boolean
        get() = assembler.floatAngle
        set(value) { assembler.floatAngle = value }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (_state.value != ConnectionState.IDLE) return@withContext
        commandSocket = DatagramSocket(null).apply {
            binder.datagram(this)
            bind(null)
            soTimeout = COMMAND_TIMEOUT_MS
            connect(host, TubeProtocol.COMMAND_PORT)
        }
        videoSocket = openVideoSocket()
        DebugLog.log("TubeClient", "connected to ${info.host}: cmd port ${commandSocket?.localPort}, video port ${videoSocket?.localPort}")
        _state.value = ConnectionState.CONNECTED
    }

    /**
     * The scope keeps streaming to every (address, port) that sent START until that port sends
     * STOP, so a fixed client port lets us STOP a previous session of ourselves before START.
     */
    private fun openVideoSocket(): DatagramSocket {
        val socket = DatagramSocket(null)
        binder.datagram(socket)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PREFERRED_VIDEO_CLIENT_PORT))
        } catch (e: SocketException) {
            socket.bind(null)
        }
        socket.soTimeout = VIDEO_TIMEOUT_MS
        socket.trafficClass = 0x10 // IPTOS_LOWDELAY
        socket.receiveBufferSize = 5 * 1024 * 1024
        socket.connect(host, TubeProtocol.VIDEO_PORT)
        return socket
    }

    override suspend fun start() {
        val video = videoSocket ?: throw IllegalStateException("connect() first")
        if (_state.value == ConnectionState.STREAMING) return
        assembler.reset()
        lastFrameAtMs = 0L
        _state.value = ConnectionState.STREAMING
        streamJob = scope.launch { receiveLoop(video) }
        restartVideo(video)
        // Same as the vendor app entering its camera screen: tip light on at the saved level.
        sendCommand(TubeProtocol.triggerLed(0, true))
        keepAliveJob = scope.launch { keepAliveLoop(video) }
    }

    override suspend fun stop() {
        if (_state.value != ConnectionState.STREAMING) return
        keepAliveJob?.cancel()
        streamJob?.cancel()
        keepAliveJob = null
        streamJob = null
        withContext(Dispatchers.IO) {
            videoSocket?.let { runCatching { send(it, TubeProtocol.STOP_VIDEO) } }
        }
        sendCommand(TubeProtocol.triggerLed(0, false))
        _state.value = ConnectionState.CONNECTED
    }

    override suspend fun setLight(percent: Int) {
        sendCommand(TubeProtocol.setBrightness(percent.coerceIn(0, 100)))
        sendCommand(TubeProtocol.COMMIT_BRIGHTNESS)
    }

    override suspend fun getLight(): Int? {
        val reply = exchange(TubeProtocol.QUERY_BRIGHTNESS) ?: return null
        if (reply.isEmpty()) return null
        val level = reply[0].toInt() and 0xFF
        return level.takeIf { it in 0..100 }
    }

    /** Battery request/reply. Also acts as the stream keep-alive. */
    suspend fun readBattery(): BatteryStatus? {
        val reply = exchange(TubeProtocol.GET_BATTERY)
        if (reply == null) {
            DebugLog.log("TubeClient", "battery: no reply from ${info.host}:${TubeProtocol.COMMAND_PORT}")
            return null
        }
        val b = TubeProtocol.parseBattery(reply, reply.size) ?: return null
        return BatteryStatus(
            percent = b.percent.coerceIn(0, 100),
            charging = b.charging || b.full,
            rawState = b.state,
        )
    }

    /** The JSON board-info document, or null on timeout. */
    suspend fun readBoardInfo(): String? {
        val reply = exchange(
            TubeProtocol.GET_BOARD_INFO,
            maxDatagrams = 8,
            isComplete = TubeProtocol::isBoardInfoComplete,
        ) ?: return null
        return reply.toString(Charsets.UTF_8)
    }

    override fun close() {
        _state.value = ConnectionState.CLOSED
        scope.cancel()
        videoSocket?.let { s ->
            runCatching { if (!s.isClosed) send(s, TubeProtocol.STOP_VIDEO) }
            runCatching { s.close() }
        }
        commandSocket?.let { runCatching { it.close() } }
        videoSocket = null
        commandSocket = null
    }

    // ---- internals --------------------------------------------------------

    private suspend fun restartVideo(video: DatagramSocket) = withContext(Dispatchers.IO) {
        DebugLog.log("TubeClient", "video STOP+START (frames so far ${assembler.framesCompleted}, dropped ${assembler.framesDropped})")
        runCatching { send(video, TubeProtocol.STOP_VIDEO) }
        delay(100)
        runCatching { send(video, TubeProtocol.START_VIDEO) }
        lastStartAtMs = System.currentTimeMillis()
    }

    private suspend fun receiveLoop(video: DatagramSocket) {
        val buffer = ByteArray(TubeProtocol.MAX_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        while (scope.isActive && !video.isClosed) {
            try {
                packet.length = buffer.size
                video.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (video.isClosed) return
                continue
            }
            val frame = assembler.offer(packet.data, packet.length) ?: continue
            val now = System.currentTimeMillis()
            if (lastFrameAtMs == 0L) DebugLog.log("TubeClient", "first frame: ${frame.length} bytes, angle ${frame.angleDegrees}")
            lastFrameAtMs = now
            _frames.tryEmit(VideoFrame(frame.toByteArray(), frame.angleDegrees, System.nanoTime()))
        }
    }

    /**
     * Once a second: poll the battery (which the scope treats as a keep-alive) and, if the
     * picture has stalled for a few seconds, bounce the stream the way the vendor app does.
     */
    private suspend fun keepAliveLoop(video: DatagramSocket) {
        while (scope.isActive) {
            readBattery()?.let { _battery.value = it }
            val now = System.currentTimeMillis()
            val stalledSince = if (lastFrameAtMs > 0) lastFrameAtMs else lastStartAtMs
            if (now - stalledSince > STALL_RESTART_MS && now - lastStartAtMs > STALL_RESTART_MS) {
                restartVideo(video)
            }
            delay(KEEP_ALIVE_INTERVAL_MS)
        }
    }

    private fun send(socket: DatagramSocket, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size))
    }

    /** Fire-and-forget command on the command port. */
    private suspend fun sendCommand(request: ByteArray) = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val socket = commandSocket ?: return@withContext
            runCatching { send(socket, request) }
        }
    }

    /**
     * Send [request] and collect up to [maxDatagrams] reply datagrams, stopping early once
     * [isComplete] says the accumulated reply is whole. Returns null on timeout/no reply.
     */
    private suspend fun exchange(
        request: ByteArray,
        maxDatagrams: Int = 1,
        isComplete: (ByteArray, Int) -> Boolean = { _, _ -> true },
    ): ByteArray? = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val socket = commandSocket ?: return@withContext null
            val buffer = ByteArray(TubeProtocol.MAX_DATAGRAM)
            val packet = DatagramPacket(buffer, buffer.size)
            drain(socket, packet)
            try {
                send(socket, request)
            } catch (e: IOException) {
                return@withContext null
            }
            val out = ByteArrayOutputStream()
            repeat(maxDatagrams) {
                try {
                    packet.length = buffer.size
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return@withContext out.toByteArray().takeIf { it.isNotEmpty() }
                } catch (e: PortUnreachableException) {
                    return@withContext null
                } catch (e: IOException) {
                    return@withContext null
                }
                out.write(buffer, 0, packet.length)
                val soFar = out.toByteArray()
                if (isComplete(soFar, soFar.size)) return@withContext soFar
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }

    /** Throw away stale replies so the next receive matches the request we are about to send. */
    private fun drain(socket: DatagramSocket, packet: DatagramPacket) {
        val previous = socket.soTimeout
        try {
            socket.soTimeout = 1
            while (true) {
                packet.length = packet.data.size
                socket.receive(packet)
            }
        } catch (e: IOException) {
            // timeout: nothing left
        } finally {
            socket.soTimeout = previous
        }
    }

    companion object {
        const val PREFERRED_VIDEO_CLIENT_PORT = 58081
        const val COMMAND_TIMEOUT_MS = 500
        const val VIDEO_TIMEOUT_MS = 100
        const val KEEP_ALIVE_INTERVAL_MS = 1000L
        const val STALL_RESTART_MS = 3000L
    }
}
