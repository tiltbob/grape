package io.github.tiltbob.grape.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tiltbob.grape.GrapeApp
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.camera.CameraProtocol
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.databinding.ActivityMainBinding
import io.github.tiltbob.grape.discovery.CameraDiscovery
import io.github.tiltbob.grape.net.NetworkLink
import io.github.tiltbob.grape.protocol.ml.MlProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val link: NetworkLink get() = (application as GrapeApp).link
    private val adapter = CameraListAdapter(::openCamera)
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvCameras.layoutManager = LinearLayoutManager(this)
        binding.rvCameras.adapter = adapter

        binding.btnWifiSettings.setOnClickListener { openWifiSettings() }
        binding.btnUseCurrentWifi.setOnClickListener { link.requestWifi() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.btnConnectPrefix.setOnClickListener {
                val prefix = binding.etSsidPrefix.text?.toString()?.trim().orEmpty()
                if (prefix.isEmpty()) {
                    binding.etSsidPrefix.error = getString(R.string.hint_ssid_prefix)
                } else {
                    link.requestWifiBySsidPrefix(prefix)
                }
            }
        } else {
            binding.rowPrefix.isVisible = false
        }
        binding.btnScan.setOnClickListener { scan() }
        binding.btnOpenManual.setOnClickListener { openManual() }
        binding.etHost.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openManual()
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                link.status.collect { renderNetwork(it) }
            }
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
                val suffix = if (hosts.isEmpty()) "" else "  (gateway ${hosts.joinToString()})"
                getString(R.string.network_linked, me) + suffix
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

    private fun scan() {
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
            adapter.submit(found)
            binding.tvScanStatus.text = if (found.isEmpty()) {
                getString(R.string.scan_none)
            } else {
                getString(R.string.scan_found, found.size)
            }
            binding.progressScan.isVisible = false
            binding.btnScan.isEnabled = true
        }
    }

    /** Ask the given address which protocol it speaks, then open it; default to tube if silent. */
    private fun openManual() {
        val host = binding.etHost.text?.toString()?.trim().orEmpty()
        if (!IPV4.matches(host)) {
            binding.etHost.error = getString(R.string.error_bad_host)
            return
        }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            binding.progressScan.isVisible = true
            binding.btnOpenManual.isEnabled = false
            val found = try {
                CameraDiscovery.scan(link.binder(), listOf(host), timeoutMs = 1200).firstOrNull { it.host == host }
            } catch (e: Exception) {
                null
            }
            binding.progressScan.isVisible = false
            binding.btnOpenManual.isEnabled = true
            val fallback = if (host == MlProtocol.DEFAULT_HOST) CameraProtocol.ML else CameraProtocol.TUBE
            openCamera(found ?: DeviceInfo(host = host, protocol = fallback))
        }
    }

    private fun openCamera(info: DeviceInfo) {
        startActivity(ViewerActivity.intent(this, info))
    }

    companion object {
        private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
    }
}
