package io.github.tiltbob.grape.ui

import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tiltbob.grape.GrapeApp
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.databinding.ActivityMainBinding
import io.github.tiltbob.grape.debug.DebugLog
import io.github.tiltbob.grape.debug.DebugReport
import io.github.tiltbob.grape.discovery.CameraDiscovery
import io.github.tiltbob.grape.net.CameraWifi
import io.github.tiltbob.grape.net.KnownScope
import io.github.tiltbob.grape.net.KnownScopes
import io.github.tiltbob.grape.net.NearbyCamera
import io.github.tiltbob.grape.net.NearbyScanner
import io.github.tiltbob.grape.net.NetworkLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The scope is always its own Wi-Fi access point, so the only flow is: hear it over
 * Bluetooth / Wi-Fi, join its network, find the camera on that network, open the viewer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: GrapeApp get() = application as GrapeApp
    private val link: NetworkLink get() = app.link
    private val scanner: NearbyScanner get() = app.scanner
    private val known: KnownScopes get() = app.known

    private val nearbyAdapter = NearbyListAdapter(::connectAndOpen, ::confirmForget)
    private var findJob: Job? = null

    /** True between asking Android to join a scope's Wi-Fi and opening the viewer. */
    private var pendingJoin = false
    private var pendingSsid: String? = null

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

        binding.btnFindNearby.setOnClickListener {
            autoJoinDone = false
            findNearby()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.btnPickWifi.setOnClickListener { joinByPicker() }
        } else {
            binding.btnPickWifi.isVisible = false
        }
        binding.btnShareLog.setOnClickListener {
            DebugLog.log("Main", "tap: Share log")
            runCatching { DebugReport.share(this, link) }
                .onFailure { DebugLog.log("Main", "share failed", it) }
        }
        binding.btnCopyLog.setOnClickListener {
            DebugReport.copy(this, link)
            Toast.makeText(this, R.string.debug_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnClearLog.setOnClickListener { DebugLog.clear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { link.status.collect { onLinkStatus(it) } }
                launch { scanner.cameras.collect { onNearby(it) } }
                launch { known.scopes.collect { refreshList() } }
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
        DebugLog.log("Main", "onStart: link=${link.status.value} permissions=${hasAllScanPermissions()} pendingJoin=$pendingJoin")
        // Listen right away when we already may; never prompt without a tap.
        if (hasAllScanPermissions() && !scanner.scanning.value && !pendingJoin &&
            link.status.value != NetworkLink.Status.AVAILABLE
        ) {
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
        scanner.namePrefixes = CameraWifi.DEFAULT_NAME_PREFIXES
        scanner.start()
    }

    private fun onScanningChanged(scanning: Boolean) {
        binding.progressNearby.isVisible = scanning || pendingJoin
        binding.btnFindNearby.isEnabled = !scanning && !pendingJoin
        if (pendingJoin) return
        binding.tvNearbyStatus.text = if (scanning) getString(R.string.nearby_scanning) else idleStatus()
    }

    /** What to say under the list when nothing is in progress. */
    private fun idleStatus(): String {
        val heard = scanner.cameras.value.size
        val remembered = known.scopes.value.size
        return when {
            heard > 0 -> getString(R.string.nearby_found, heard)
            remembered > 0 -> getString(R.string.nearby_remembered, remembered)
            else -> getString(R.string.nearby_none)
        }
    }

    /** Heard scopes first, completed from memory, then remembered ones nobody hears yet. */
    private fun refreshList() {
        nearbyAdapter.submit(KnownScope.listing(scanner.cameras.value, known.scopes.value))
        if (!pendingJoin && !scanner.scanning.value) binding.tvNearbyStatus.text = idleStatus()
    }

    private fun onNearby(list: List<NearbyCamera>) {
        refreshList()
        if (!pendingJoin && list.isNotEmpty()) {
            binding.tvNearbyStatus.text = getString(R.string.nearby_found, list.size)
        }
        // The scope we used last time is switched on again: rejoin it without a tap.
        val last = known.lastUsed ?: return
        if (autoJoinDone || pendingJoin || link.status.value == NetworkLink.Status.AVAILABLE) return
        val match = list.firstOrNull { it.ssid.equals(last.ssid, ignoreCase = true) } ?: return
        autoJoinDone = true
        DebugLog.log("Main", "heard the last used scope ${match.ssid}: rejoining")
        connectAndOpen(last.complete(match))
    }

    /** Join the scope's Wi-Fi; [onLinkStatus] continues once Android reports the network. */
    private fun connectAndOpen(camera: NearbyCamera) {
        scanner.stop()
        pendingJoin = true
        pendingSsid = camera.ssid
        known.remember(camera)
        binding.tvNearbyStatus.text = getString(R.string.nearby_joining, camera.ssid)
        binding.progressNearby.isVisible = true
        binding.btnFindNearby.isEnabled = false
        link.connectTo(camera)
    }

    private fun confirmForget(camera: NearbyCamera) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.forget_title, camera.ssid))
            .setMessage(R.string.forget_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_forget) { _, _ -> known.forget(camera.ssid) }
            .show()
    }

    /** Fallback when the radios cannot hear the scope: let Android list matching networks. */
    private fun joinByPicker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        DebugLog.log("Main", "tap: Pick its Wi-Fi")
        scanner.stop()
        pendingJoin = true
        pendingSsid = null
        binding.tvNearbyStatus.text = getString(R.string.nearby_choose)
        binding.progressNearby.isVisible = true
        binding.btnFindNearby.isEnabled = false
        link.requestWifiBySsidPrefix(CameraWifi.DEFAULT_NAME_PREFIXES.first())
    }

    // ---- joined network -----------------------------------------------------------

    private fun onLinkStatus(status: NetworkLink.Status) {
        if (!pendingJoin) return
        val ssid = pendingSsid ?: link.targetSsid ?: getString(R.string.the_scope_wifi)
        when (status) {
            NetworkLink.Status.AVAILABLE -> findCameraOnJoinedNetwork(ssid, rememberAs = pendingSsid ?: link.targetSsid)
            NetworkLink.Status.UNAVAILABLE, NetworkLink.Status.LOST -> {
                finishJoin()
                binding.tvNearbyStatus.text = if (pendingSsid != null) {
                    getString(R.string.nearby_join_failed, ssid)
                } else {
                    getString(R.string.nearby_join_failed_generic)
                }
            }
            else -> Unit
        }
    }

    /**
     * The joined network is the scope's own: ask it for the camera and open the viewer.
     * [rememberAs] is the SSID to file the camera's details under, when we know it.
     */
    private fun findCameraOnJoinedNetwork(ssid: String, rememberAs: String?) {
        findJob?.cancel()
        findJob = lifecycleScope.launch {
            binding.tvNearbyStatus.text = getString(R.string.nearby_looking, ssid)
            val remembered = known.forSsid(rememberAs)?.device
            // The camera's address from last time goes first in line, in case the network's
            // DHCP server or gateway is not the camera itself.
            val hosts = (listOfNotNull(remembered?.host) + link.candidateHosts()).distinct()
            DebugLog.log("Main", "joined $ssid: link=${link.status.value} local=${link.localAddress()} candidates=$hosts remembered=${remembered?.let { "${it.host}/${it.protocol}/${it.model}" }}")
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            // Without this lock many phones' Wi-Fi drivers drop broadcast datagrams such as the beacon.
            val lock = wifi?.createMulticastLock("eardigger-scan")?.apply { setReferenceCounted(false); acquire() }
            val found = try {
                CameraDiscovery.scan(link.binder(), hosts)
            } catch (e: Exception) {
                DebugLog.log("Main", "scan failed", e)
                emptyList()
            } finally {
                runCatching { lock?.release() }
            }
            finishJoin()
            var first = found.firstOrNull()
            if (first != null) {
                // A beacon-only answer says less than the board info we kept from last time.
                if (first.rawInfo == null && remembered?.rawInfo != null && remembered.host == first.host) {
                    first = remembered
                }
                if (rememberAs != null) known.learn(rememberAs, first)
                binding.tvNearbyStatus.text = getString(R.string.nearby_found, 1)
                openCamera(first)
            } else {
                binding.tvNearbyStatus.text = getString(R.string.nearby_joined_no_camera, ssid)
            }
        }
    }

    private fun finishJoin() {
        pendingJoin = false
        pendingSsid = null
        binding.progressNearby.isVisible = scanner.scanning.value
        binding.btnFindNearby.isEnabled = !scanner.scanning.value
    }

    private fun openCamera(info: DeviceInfo) {
        DebugLog.log("Main", "open camera ${info.host} ${info.protocol}")
        startActivity(ViewerActivity.intent(this, info))
    }

    private companion object {
        const val DEBUG_TAIL_LINES = 12
    }
}
