package io.github.tiltbob.grape.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds scopes that are switched on nearby, the way the vendor app does:
 *  - a BLE scan for advertisements whose name starts with a known prefix (the scope's BLE
 *    name is its Wi-Fi SSID, and its BSSID is the BLE address minus two);
 *  - the system Wi-Fi scan results, filtered the same way (gives BSSID and security).
 * Both are passive radio listening on the phone; nothing is transmitted to anyone.
 */
class NearbyScanner(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetoothManager = context.applicationContext.getSystemService(BluetoothManager::class.java)

    private val _cameras = MutableStateFlow<List<NearbyCamera>>(emptyList())
    val cameras: StateFlow<List<NearbyCamera>> = _cameras.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Human-readable reasons a radio could not be used in the last scan. */
    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    var namePrefixes: List<String> = CameraWifi.DEFAULT_NAME_PREFIXES

    private var bleScanner: BluetoothLeScanner? = null
    private var bleCallback: ScanCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private val stopRunnable = Runnable { stop() }

    /** Permissions this device needs before [start] can use both radios. */
    fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Run both scans for [durationMs], merging hits into [cameras]. */
    fun start(durationMs: Long = 8000L) {
        stop()
        _cameras.value = emptyList()
        val notes = mutableListOf<String>()
        _scanning.value = true
        startBle(notes)
        startWifi(notes)
        _notes.value = notes
        handler.postDelayed(stopRunnable, durationMs)
    }

    /** Stopping only ever follows a start that already passed the permission checks. */
    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(stopRunnable)
        bleCallback?.let { cb -> runCatching { bleScanner?.stopScan(cb) } }
        bleCallback = null
        wifiReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        wifiReceiver = null
        _scanning.value = false
    }

    private fun locationServicesOn(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lm = context.getSystemService(LocationManager::class.java) ?: return false
            return lm.isLocationEnabled
        }
        @Suppress("DEPRECATION")
        val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
        @Suppress("DEPRECATION")
        return mode != Settings.Secure.LOCATION_MODE_OFF
    }

    private fun startBle(notes: MutableList<String>) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            notes += "no Bluetooth"
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!granted(permission)) {
            notes += "Bluetooth scan permission not granted"
            return
        }
        if (!adapter.isEnabled) {
            notes += "Bluetooth is off"
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationServicesOn()) {
            notes += "Location services are off (Android needs them for Bluetooth scanning)"
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            notes += "Bluetooth LE scanner unavailable"
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onBle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onBle)
            override fun onScanFailed(errorCode: Int) {
                _notes.value = _notes.value + "Bluetooth scan failed ($errorCode)"
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            @SuppressLint("MissingPermission") // checked above for the current API level
            scanner.startScan(null, settings, cb)
            bleScanner = scanner
            bleCallback = cb
        } catch (e: SecurityException) {
            notes += "Bluetooth scan not permitted"
        }
    }

    private fun onBle(result: ScanResult) {
        // The advertised name needs no BLUETOOTH_CONNECT permission, unlike device.name.
        val name = result.scanRecord?.deviceName?.trim() ?: return
        if (!CameraWifi.looksLikeCamera(name, namePrefixes)) return
        val address = result.device?.address ?: return
        add(
            NearbyCamera(
                ssid = name,
                bssid = CameraWifi.bssidFromBleAddress(address),
                bleAddress = address,
                seenByBluetooth = true,
                rssi = result.rssi,
            ),
        )
    }

    private fun startWifi(notes: MutableList<String>) {
        val wm = wifiManager ?: run {
            notes += "no Wi-Fi"
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!granted(permission)) {
            notes += "Wi-Fi scan permission not granted"
            return
        }
        if (!wm.isWifiEnabled) {
            notes += "Wi-Fi is off"
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !locationServicesOn()) {
            notes += "Location services are off (Android needs them for Wi-Fi scanning)"
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = readWifiResults(wm)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        wifiReceiver = receiver
        // Cached results first (Android throttles active scans), then ask for a fresh one.
        readWifiResults(wm)
        @Suppress("DEPRECATION")
        runCatching { wm.startScan() }
    }

    private fun readWifiResults(wm: WifiManager) {
        val results = try {
            wm.scanResults
        } catch (e: SecurityException) {
            return
        }
        for (r in results) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                r.wifiSsid?.toString()?.trim('"')
            } else {
                @Suppress("DEPRECATION") r.SSID
            }
            if (!CameraWifi.looksLikeCamera(ssid, namePrefixes)) continue
            add(
                NearbyCamera(
                    ssid = ssid!!,
                    bssid = r.BSSID?.takeIf { it.isNotBlank() },
                    capabilities = r.capabilities,
                    seenByWifi = true,
                    rssi = r.level,
                ),
            )
        }
    }

    @Synchronized
    private fun add(camera: NearbyCamera) {
        val list = _cameras.value.toMutableList()
        val i = list.indexOfFirst { it.ssid.equals(camera.ssid, ignoreCase = true) }
        if (i >= 0) list[i] = list[i].merge(camera) else list += camera
        _cameras.value = list.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
    }
}
