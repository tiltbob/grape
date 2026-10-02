package io.github.tiltbob.grape.protocol.ml

import io.github.tiltbob.grape.camera.BatteryStatus
import io.github.tiltbob.grape.camera.CameraClient
import io.github.tiltbob.grape.camera.ConnectionState
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.SocketBinder
import io.github.tiltbob.grape.camera.VideoFrame
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CameraClient] for the "ML" protocol. See [MlProtocol] for the wire format.
 *
 * Like the vendor's native library it listens on both video channels at once: the UDP stream
 * on port 8030 (which needs a START every 600 ms) and the TCP stream on port 7060 (which
 * starts as soon as someone connects).
 */
class MlCameraClient(
    override val info: DeviceInfo,
    private val binder: SocketBinder = SocketBinder.NONE,
) : CameraClient {

    private val host: InetAddress = InetAddress.getByName(info.host)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ml-client"))
    private val commandMutex = Mutex()
    private val sequence = AtomicInteger(1)

    private var commandSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private var tcpJob: Job? = null
    private var keepAliveJob: Job? = null

    private val angleDecoder = MlProtocol.AngleDecoder()
    @Volatile private var lastAngle = 0f

    /** Model name used to pick the battery voltage curve; refreshed from board info. */
    @Volatile var model: String? = info.model

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

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (_state.value != ConnectionState.IDLE) return@withContext
        commandSocket = DatagramSocket(null).apply {
            binder.datagram(this)
            bind(null)
            soTimeout = COMMAND_TIMEOUT_MS
        }
        _state.value = ConnectionState.CONNECTED
    }

    override suspend fun start() {
        if (_state.value == ConnectionState.IDLE) throw IllegalStateException("connect() first")
        if (_state.value == ConnectionState.STREAMING) return
        _state.value = ConnectionState.STREAMING
        udpJob = scope.launch { udpVideoLoop() }
        tcpJob = scope.launch { tcpVideoLoop() }
        keepAliveJob = scope.launch {
            while (isActive) {
                readBattery()?.let { _battery.value = it }
                delay(BATTERY_INTERVAL_MS)
            }
        }
    }

    override suspend fun stop() {
        if (_state.value != ConnectionState.STREAMING) return
        keepAliveJob?.cancel()
        udpJob?.cancel()
        tcpJob?.cancel()
        keepAliveJob = null
        udpJob = null
        tcpJob = null
        _state.value = ConnectionState.CONNECTED
    }

    override suspend fun setLight(percent: Int) {
        exchange(MlProtocol.CMD_SET_LIGHT, percent.coerceIn(0, 100))
    }

    override suspend fun getLight(): Int? {
        val (reply, n) = exchange(MlProtocol.CMD_GET_LIGHT) ?: return null
        return MlProtocol.replyInt(reply, n)?.takeIf { it in 0..100 }
    }

    suspend fun readBattery(): BatteryStatus? {
        val (reply, n) = exchange(MlProtocol.CMD_GET_BATTERY) ?: return null
        val b = MlProtocol.parseBattery(reply, n) ?: return null
        return BatteryStatus(
            percent = b.percentDirect ?: b.percentEstimate(model),
            charging = b.charging,
            rawState = b.raw,
        )
    }

    /** Firmware version number (the vendor app derives the model from its leading digits). */
    suspend fun readVersion(): Int? {
        val (reply, n) = exchange(MlProtocol.CMD_GET_VERSION) ?: return null
        return MlProtocol.replyInt(reply, n)
    }

    /** The JSON board-info document, or null if the device does not answer. */
    suspend fun readBoardInfo(): String? {
        val (reply, n) = exchange(MlProtocol.CMD_GET_BOARD_INFO, replySize = MlProtocol.MAX_COMMAND_REPLY)
            ?: return null
        return MlProtocol.parseBoardInfo(reply, n)
    }

    override fun close() {
        _state.value = ConnectionState.CLOSED
        scope.cancel()
        commandSocket?.let { runCatching { it.close() } }
        commandSocket = null
    }

    // ---- internals --------------------------------------------------------

    /**
     * Send a command and wait for the matching reply, retrying like the vendor library does.
     * Returns the reply buffer and its length, or null.
     */
    private suspend fun exchange(
        command: Int,
        parameter: Int = 0,
        replySize: Int = 0x400,
        attempts: Int = 3,
    ): Pair<ByteArray, Int>? = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val socket = commandSocket ?: return@withContext null
            val request = MlProtocol.request(command, sequence.getAndIncrement(), parameter)
            val buffer = ByteArray(replySize)
            val packet = DatagramPacket(buffer, buffer.size)
            repeat(attempts) {
                try {
                    socket.send(DatagramPacket(request, request.size, host, MlProtocol.COMMAND_PORT))
                    packet.length = buffer.size
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return@repeat
                } catch (e: IOException) {
                    return@withContext null
                }
                if (MlProtocol.replyMatches(buffer, packet.length, command)) {
                    return@withContext buffer to packet.length
                }
            }
            null
        }
    }

    private suspend fun udpVideoLoop() {
        val socket = try {
            DatagramSocket(null).apply {
                binder.datagram(this)
                bind(null)
                soTimeout = VIDEO_TIMEOUT_MS
                receiveBufferSize = 2 * 1024 * 1024
            }
        } catch (e: IOException) {
            return
        }
        val start = MlProtocol.videoControl(MlProtocol.VIDEO_START)
        val stop = MlProtocol.videoControl(MlProtocol.VIDEO_STOP)
        val assembler = MlFrameAssembler()
        val buffer = ByteArray(MlProtocol.MAX_VIDEO_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        var lastStartMs = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now - lastStartMs > MlProtocol.VIDEO_KEEPALIVE_MS) {
                    runCatching { socket.send(DatagramPacket(start, start.size, host, MlProtocol.VIDEO_PORT)) }
                    lastStartMs = now
                }
                try {
                    packet.length = buffer.size
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (socket.isClosed) return
                    continue
                }
                val chunk = MlProtocol.parseVideoChunk(buffer, packet.length) ?: continue
                val frame = assembler.offer(chunk, buffer) ?: continue
                emit(frame.jpeg, frame.angleRaw)
            }
        } finally {
            runCatching { socket.send(DatagramPacket(stop, stop.size, host, MlProtocol.VIDEO_PORT)) }
            runCatching { socket.close() }
        }
    }

    private suspend fun tcpVideoLoop() {
        val block = ByteArray(32 * 1024)
        while (currentCoroutineContext().isActive) {
            val socket = Socket()
            try {
                binder.stream(socket)
                socket.connect(InetSocketAddress(host, MlProtocol.TCP_VIDEO_PORT), TCP_CONNECT_TIMEOUT_MS)
                socket.soTimeout = TCP_READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val parser = MlTcpStreamParser({ jpeg, header -> emit(jpeg, header.angleRaw) })
                val input = socket.getInputStream()
                while (currentCoroutineContext().isActive) {
                    val n = try {
                        input.read(block)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (n < 0) break
                    parser.feed(block, n)
                }
            } catch (e: IOException) {
                // not reachable on TCP (most units stream over UDP only); retry slowly
            } finally {
                runCatching { socket.close() }
            }
            delay(TCP_RETRY_MS)
        }
    }

    private fun emit(jpeg: ByteArray, angleRaw: Int) {
        val angle = angleDecoder.decode(angleRaw)
        if (angle != null) lastAngle = angle
        _frames.tryEmit(VideoFrame(jpeg, lastAngle, System.nanoTime()))
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 200
        const val VIDEO_TIMEOUT_MS = 100
        const val BATTERY_INTERVAL_MS = 1000L
        const val TCP_CONNECT_TIMEOUT_MS = 3000
        const val TCP_READ_TIMEOUT_MS = 3000
        const val TCP_RETRY_MS = 2000L
    }
}
