package io.github.tiltbob.grape.ui

import android.content.Intent
import android.content.pm.PackageManager
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
import io.github.tiltbob.grape.discovery.CameraDiscovery
import io.github.tiltbob.grape.net.CameraWifi
import io.github.tiltbob.grape.net.NearbyCamera
import io.github.tiltbob.grape.net.NearbyScanner
import io.github.tiltbob.grape.net.NetworkLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
        binding.btnScan.setOnClickListener { scanNetwork(openFirst = false) }

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
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
            val found = try {
                CameraDiscovery.scan(link.binder(), link.candidateHosts())
            } catch (e: Exception) {
                emptyList()
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

    private fun openCamera(info: DeviceInfo) {
        startActivity(ViewerActivity.intent(this, info))
    }

}
