package io.github.tiltbob.grape.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import io.github.tiltbob.grape.GrapeApp
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.camera.BatteryStatus
import io.github.tiltbob.grape.camera.CameraClient
import io.github.tiltbob.grape.camera.CameraClients
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.camera.DeviceInfoJson
import io.github.tiltbob.grape.camera.VideoFrame
import io.github.tiltbob.grape.databinding.ActivityViewerBinding
import io.github.tiltbob.grape.debug.DebugLog
import io.github.tiltbob.grape.net.NetworkLink
import io.github.tiltbob.grape.protocol.ml.MlCameraClient
import io.github.tiltbob.grape.protocol.ml.MlDiscovery
import io.github.tiltbob.grape.protocol.tube.TubeCameraClient
import io.github.tiltbob.grape.protocol.tube.TubeDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var info: DeviceInfo

    private var client: CameraClient? = null
    private var sessionJob: Job? = null

    @Volatile private var lastFrame: VideoFrame? = null
    @Volatile private var lastFrameAtMs = 0L
    private var autoRotate = true
    private var shownAngle = 0f
    private var suppressSlider = false

    // Ring of decode targets so the UI thread never draws the bitmap being decoded into.
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var nextBuffer = 0
    private val decodeOptions = BitmapFactory.Options().apply { inMutable = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Grape_Viewer)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        info = DeviceInfoJson.decode(intent.getStringExtra(EXTRA_DEVICE) ?: error("missing device"))
        binding.tvDevice.text = info.displayName
        binding.tvBattery.text = getString(R.string.viewer_battery_unknown)
        binding.tvFps.text = ""
        binding.tvLightValue.text = ""

        binding.sliderLight.addOnChangeListener { _, value, _ ->
            binding.tvLightValue.text = value.toInt().toString()
        }
        binding.sliderLight.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (suppressSlider) return
                val level = slider.value.toInt()
                lifecycleScope.launch { runCatching { client?.setLight(level) } }
            }
        })
        // A circle is the one shape that does not seem to turn when the picture is kept upright.
        binding.cameraView.circular = autoRotate
        binding.btnRotation.setOnClickListener {
            autoRotate = !autoRotate
            binding.btnRotation.setText(if (autoRotate) R.string.btn_rotation_auto else R.string.btn_rotation_off)
            binding.cameraView.circular = autoRotate
            if (!autoRotate) shownAngle = 0f
        }
        binding.btnSnapshot.setOnClickListener { snapshot() }
    }

    override fun onStart() {
        super.onStart()
        sessionJob = lifecycleScope.launch { runSession() }
    }

    override fun onStop() {
        super.onStop()
        DebugLog.log("Viewer", "close ${info.host}")
        sessionJob?.cancel()
        sessionJob = null
        val c = client
        client = null
        if (c != null) {
            // Stop the stream politely off the main thread, then drop the sockets.
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { c.stop() }
                runCatching { c.close() }
            }
        }
        binding.cameraView.clear()
    }

    private suspend fun runSession() {
        val link = (application as GrapeApp).link
        binding.tvStatus.isVisible = true
        binding.tvStatus.text = getString(R.string.viewer_connecting, info.displayName)

        DebugLog.log("Viewer", "open ${info.host} ${info.protocol} model=${info.model} link=${link.status.value}")
        val c = try {
            CameraClients.create(info, link.binder()).also {
                it.connect()
                it.start()
            }
        } catch (e: Exception) {
            DebugLog.log("Viewer", "connect failed", e)
            binding.tvStatus.text = getString(R.string.viewer_error, e.message ?: e.javaClass.simpleName)
            return
        }
        client = c
        binding.tvStatus.text = getString(R.string.viewer_waiting)

        // Everything below is a child of the session job, so leaving the screen cancels it all.
        coroutineScope {
            // Fill in details we did not have yet (manual IP entry, beacon-only discovery).
            if (info.rawInfo == null) {
                launch {
                    val parsed = when (c) {
                        is TubeCameraClient -> c.readBoardInfo()?.let { TubeDiscovery.parseBoardInfo(info.host, it) }
                            ?.also { c.floatAngle = it.floatAngle }
                        is MlCameraClient -> c.readBoardInfo()?.let { MlDiscovery.fromBoardInfo(info.host, it) }
                            ?.also { c.model = it.model }
                        else -> null
                    } ?: return@launch
                    info = parsed
                    binding.tvDevice.text = info.displayName
                }
            }
            launch {
                val level = runCatching { c.getLight() }.getOrNull() ?: return@launch
                suppressSlider = true
                binding.sliderLight.value = level.toFloat()
                suppressSlider = false
            }
            launch { c.battery.collect { renderBattery(it) } }
            launch { watchStall() }
            launch { watchLink(link) }
            launch(Dispatchers.Default) { decodeLoop(c) }
        }
    }

    /** The scope's Wi-Fi going away (it was switched off, or walked out of range) ends the session. */
    private suspend fun watchLink(link: NetworkLink) {
        link.status.collect { status ->
            if (status == NetworkLink.Status.LOST || status == NetworkLink.Status.NONE) {
                DebugLog.log("Viewer", "scope Wi-Fi $status: closing the viewer")
                Toast.makeText(this, R.string.viewer_link_lost, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun decodeLoop(c: CameraClient) {
        var windowStart = System.currentTimeMillis()
        var windowFrames = 0
        c.frames.collect { frame ->
            val bitmap = decode(frame.jpeg) ?: return@collect
            lastFrame = frame
            lastFrameAtMs = System.currentTimeMillis()
            windowFrames++
            val now = lastFrameAtMs
            val fps = if (now - windowStart >= 1000) {
                val f = windowFrames * 1000 / (now - windowStart)
                windowStart = now
                windowFrames = 0
                f.toInt()
            } else {
                -1
            }
            withContext(Dispatchers.Main) {
                if (binding.tvStatus.isVisible) binding.tvStatus.isVisible = false
                binding.cameraView.setFrame(bitmap, displayAngle(frame.angleDegrees))
                if (fps >= 0) binding.tvFps.text = getString(R.string.viewer_fps, fps)
            }
        }
    }

    /** Apply the roll angle with a little hysteresis so the picture does not jitter. */
    private fun displayAngle(reported: Float): Float {
        if (!autoRotate) return 0f
        var diff = abs(reported - shownAngle)
        if (diff > 180f) diff = 360f - diff
        if (diff >= ANGLE_HYSTERESIS_DEGREES) shownAngle = reported
        return shownAngle
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val target = buffers[nextBuffer]
        decodeOptions.inBitmap = target
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
        } catch (e: IllegalArgumentException) {
            // Size changed: the old buffer cannot be reused.
            decodeOptions.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
        } ?: return null
        buffers[nextBuffer] = bitmap
        nextBuffer = (nextBuffer + 1) % buffers.size
        return bitmap
    }

    private suspend fun watchStall() {
        while (currentCoroutineIsActive()) {
            delay(1000)
            val last = lastFrameAtMs
            if (last > 0 && System.currentTimeMillis() - last > STALL_NOTICE_MS && !binding.tvStatus.isVisible) {
                binding.tvStatus.text = getString(R.string.viewer_waiting)
                binding.tvStatus.isVisible = true
            }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private fun renderBattery(status: BatteryStatus?) {
        binding.tvBattery.text = when {
            status == null -> getString(R.string.viewer_battery_unknown)
            status.charging -> getString(R.string.viewer_battery_charging, status.percent)
            else -> getString(R.string.viewer_battery, status.percent)
        }
    }

    private fun snapshot() {
        val frame = lastFrame
        if (frame == null) {
            Toast.makeText(this, R.string.snapshot_no_frame, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { Snapshots.save(this@ViewerActivity, frame.jpeg) }
            val msg = if (where != null) getString(R.string.snapshot_saved, where) else getString(R.string.snapshot_failed)
            Toast.makeText(this@ViewerActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "device"
        private const val ANGLE_HYSTERESIS_DEGREES = 2f
        private const val STALL_NOTICE_MS = 3000L

        fun intent(context: Context, info: DeviceInfo): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_DEVICE, DeviceInfoJson.encode(info))
    }
}
