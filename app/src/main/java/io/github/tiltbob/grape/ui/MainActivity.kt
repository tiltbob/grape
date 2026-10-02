package io.github.tiltbob.grape.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tiltbob.grape.GrapeApp
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.databinding.ActivityMainBinding
import io.github.tiltbob.grape.debug.DebugLog
import io.github.tiltbob.grape.debug.DebugReport
import io.github.tiltbob.grape.discovery.CameraDiscovery
import io.github.tiltbob.grape.net.CameraWifi
import io.github.tiltbob.grape.net.NearbyCamera
import io.github.tiltbob.grape.net.NearbyScanner
import io.github.tiltbob.grape.net.NetworkLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: GrapeApp get() = application as GrapeApp
    private val link: NetworkLink get() = app.link
    private val scanner: NearbyScanner get() = app.scanner

    private val nearbyAdapter = NearbyListAdapter(::connectAndOpen)
    private val cameraAdapter = CameraListAdapter(::openCamera)

    private var scanJob: Job? = null

    /** The scope we are joining; once the link is up we look for it and open the viewer. */
    private var pendingCamera: NearbyCamera? = null

    /** Only rejoin the remembered scope by itself once per launch, so leaving the viewer
     *  does not bounce straight back into it. */
    private var autoJoinDone = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            DebugLog.log("Main", "permission result: $result")
            if (result.values.any { it }) startNearbyScan() else {
                binding.tvNearbyStatus.text = getString(R.string.permission_needed)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvNearby.layoutManager = LinearLayoutManager(this)
        binding.rvNearby.adapter = nearbyAdapter
        binding.rvCameras.layoutManager = LinearLayoutManager(this)
        binding.rvCameras.adapter = cameraAdapter
        nearbyAdapter.lastUsedSsid = app.lastCameraSsid

        binding.btnFindNearby.setOnClickListener {
            autoJoinDone = false
            findNearby()
        }
        binding.btnWifiSettings.setOnClickListener { openWifiSettings() }
        binding.btnUseCurrentWifi.setOnClickListener {
            pendingCamera = null
            link.requestWifi()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.btnConnectPrefix.setOnClickListener {
                val prefix = binding.etSsidPrefix.text?.toString()?.trim().orEmpty()
                if (prefix.isEmpty()) {
                    binding.etSsidPrefix.error = getString(R.string.hint_ssid_prefix)
                } else {
                    pendingCamera = null
                    link.requestWifiBySsidPrefix(prefix)
                }
            }
        } else {
            binding.rowPrefix.isVisible = false
        }
        binding.btnScan.setOnClickListener {
            DebugLog.log("Main", "tap: Scan")
            scanNetwork(openFirst = false)
        }
        binding.btnShareLog.setOnClickListener {
            DebugLog.log("Main", "tap: Share log")
            runCatching { DebugReport.share(this, link) }
                .onFailure { DebugLog.log("Main", "share failed", it) }
        }
        binding.btnCopyLog.setOnClickListener {
            DebugReport.copy(this, link)
            android.widget.Toast.makeText(this, R.string.debug_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.btnClearLog.setOnClickListener { DebugLog.clear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { link.status.collect { onLinkStatus(it) } }
                launch { scanner.cameras.collect { onNearby(it) } }
                launch { scanner.scanning.collect { onScanningChanged(it) } }
                launch {
                    scanner.notes.collect { notes ->
                        binding.tvNearbyNotes.isVisible = notes.isNotEmpty()
                        binding.tvNearbyNotes.text = notes.joinToString("\n")
                    }
                }
                launch { DebugLog.version.collect { binding.tvDebugTail.text = DebugLog.tail(DEBUG_TAIL_LINES) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DebugLog.log("Main", "onStart: link=${link.status.value} permissions=${hasAllScanPermissions()}")
        // Attach to whatever Wi-Fi the phone is on, so a plain Scan has a network to use.
        if (link.status.value == NetworkLink.Status.NONE) link.requestWifi()
        // Listen right away when we already may; never prompt without a tap.
        if (hasAllScanPermissions() && !scanner.scanning.value && link.status.value != NetworkLink.Status.AVAILABLE) {
            startNearbyScan()
        }
    }

    override fun onStop() {
        super.onStop()
        scanner.stop()
    }

    // ---- nearby scopes --------------------------------------------------------

    private fun hasAllScanPermissions() = scanner.requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun findNearby() {
        val missing = scanner.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        DebugLog.log("Main", "tap: Find nearby (missing permissions: $missing)")
        if (missing.isEmpty()) startNearbyScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startNearbyScan() {
        val prefix = binding.etSsidPrefix.text?.toString()?.trim().orEmpty()
        scanner.namePrefixes = (listOf(prefix) + CameraWifi.DEFAULT_NAME_PREFIXES).filter { it.isNotBlank() }.distinct()
        scanner.start()
    }

    private fun onScanningChanged(scanning: Boolean) {
        binding.progressNearby.isVisible = scanning
        binding.btnFindNearby.isEnabled = !scanning
        if (pendingCamera != null) return
        val n = scanner.cameras.value.size
        binding.tvNearbyStatus.text = when {
            scanning -> getString(R.string.nearby_scanning)
            n == 0 -> getString(R.string.nearby_none)
            else -> getString(R.string.nearby_found, n)
        }
    }

    private fun onNearby(list: List<NearbyCamera>) {
        nearbyAdapter.submit(list)
        if (pendingCamera == null) {
            if (list.isNotEmpty()) binding.tvNearbyStatus.text = getString(R.string.nearby_found, list.size)
        }
        // The scope we used last time is switched on again: rejoin it without a tap.
        val last = app.lastCameraSsid ?: return
        if (autoJoinDone || pendingCamera != null || link.status.value == NetworkLink.Status.AVAILABLE) return
        val match = list.firstOrNull { it.ssid.equals(last, ignoreCase = true) } ?: return
        autoJoinDone = true
        connectAndOpen(match)
    }

    /** Join the scope's Wi-Fi; [onLinkStatus] continues once Android reports the network. */
    private fun connectAndOpen(camera: NearbyCamera) {
        scanner.stop()
        pendingCamera = camera
        app.lastCameraSsid = camera.ssid
        nearbyAdapter.lastUsedSsid = camera.ssid
        binding.tvNearbyStatus.text = getString(R.string.nearby_joining, camera.ssid)
        binding.progressNearby.isVisible = true
        link.connectTo(camera)
    }

    // ---- network link ---------------------------------------------------------

    private fun onLinkStatus(status: NetworkLink.Status) {
        renderNetwork(status)
        val pending = pendingCamera ?: return
        when (status) {
            NetworkLink.Status.AVAILABLE -> {
                binding.progressNearby.isVisible = false
                scanNetwork(openFirst = true, joinedSsid = pending.ssid)
            }
            NetworkLink.Status.UNAVAILABLE, NetworkLink.Status.LOST -> {
                pendingCamera = null
                binding.progressNearby.isVisible = false
                binding.tvNearbyStatus.text = getString(R.string.nearby_join_failed, pending.ssid)
            }
            else -> Unit
        }
    }

    private fun renderNetwork(status: NetworkLink.Status) {
        binding.tvNetworkStatus.text = when (status) {
            NetworkLink.Status.NONE -> getString(R.string.network_none)
            NetworkLink.Status.REQUESTING -> getString(R.string.network_requesting)
            NetworkLink.Status.UNAVAILABLE -> getString(R.string.network_unavailable)
            NetworkLink.Status.LOST -> getString(R.string.network_lost)
            NetworkLink.Status.AVAILABLE -> {
                val me = link.localAddress() ?: "?"
                val hosts = link.candidateHosts()
                val name = link.targetSsid?.let { "$it, " } ?: ""
                val suffix = if (hosts.isEmpty()) "" else "  (gateway ${hosts.joinToString()})"
                getString(R.string.network_linked, name + me) + suffix
            }
        }
    }

    private fun openWifiSettings() {
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        runCatching { startActivity(panel) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
    }

    // ---- cameras on the linked network ---------------------------------------

    /** Look for cameras on the linked network; with [openFirst], open the first one found. */
    private fun scanNetwork(openFirst: Boolean, joinedSsid: String? = null) {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            binding.progressScan.isVisible = true
            binding.btnScan.isEnabled = false
            binding.tvScanStatus.text = getString(R.string.scan_running)
            ensureLinked()
            DebugLog.log("Main", "scanNetwork: link=${link.status.value} local=${link.localAddress()} candidates=${link.candidateHosts()}")
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            // Without this lock many phones' Wi-Fi drivers drop broadcast datagrams such as the beacon.
            val lock = wifi?.createMulticastLock("eardigger-scan")?.apply { setReferenceCounted(false); acquire() }
            val found = try {
                CameraDiscovery.scan(link.binder(), link.candidateHosts())
            } catch (e: Exception) {
                DebugLog.log("Main", "scan failed", e)
                emptyList()
            } finally {
                runCatching { lock?.release() }
            }
            cameraAdapter.submit(found)
            binding.tvScanStatus.text = if (found.isEmpty()) {
                getString(R.string.scan_none)
            } else {
                getString(R.string.scan_found, found.size)
            }
            binding.progressScan.isVisible = false
            binding.btnScan.isEnabled = true
            if (openFirst) {
                pendingCamera = null
                val first = found.firstOrNull()
                if (first != null) {
                    binding.tvNearbyStatus.text = getString(R.string.nearby_found, 1)
                    openCamera(first)
                } else {
                    binding.tvNearbyStatus.text = getString(R.string.nearby_joined_no_camera, joinedSsid ?: "")
                }
            }
        }
    }

    /** If no Wi-Fi network is linked yet, ask Android for the current one and wait briefly. */
    private suspend fun ensureLinked() {
        if (link.network.value != null) return
        if (link.status.value != NetworkLink.Status.REQUESTING) link.requestWifi()
        val got = withTimeoutOrNull(LINK_WAIT_MS) {
            link.status.first { it == NetworkLink.Status.AVAILABLE || it == NetworkLink.Status.UNAVAILABLE }
        }
        DebugLog.log("Main", "ensureLinked: ${got ?: "timed out"} (${link.describeNetwork().trim()})")
    }

    private fun openCamera(info: DeviceInfo) {
        startActivity(ViewerActivity.intent(this, info))
    }


    private companion object {
        const val DEBUG_TAIL_LINES = 12
        const val LINK_WAIT_MS = 4000L
    }
}
