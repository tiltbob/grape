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
import io.github.tiltbob.grape.debug.DebugLog
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

    private companion object {
        const val TAG = "Nearby"
    }

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

    /**
     * Permissions this device needs before [start] can use both radios. Wi-Fi scan results
     * are gated behind fine location on every Android version (NEARBY_WIFI_DEVICES alone
     * gets an empty list), so location is always on the list.
     */
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private val seenBle = HashSet<String>()
    private val seenWifi = HashSet<String>()
    private var bleHits = 0
    private var wifiResults = 0

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Run both scans for [durationMs], merging hits into [cameras]. */
    fun start(durationMs: Long = 8000L) {
        stop()
        _cameras.value = emptyList()
        seenBle.clear()
        seenWifi.clear()
        bleHits = 0
        wifiResults = 0
        val notes = mutableListOf<String>()
        _scanning.value = true
        DebugLog.log(TAG, "start(): prefixes=$namePrefixes duration=${durationMs}ms")
        startBle(notes)
        startWifi(notes)
        _notes.value = notes
        if (notes.isNotEmpty()) DebugLog.log(TAG, "notes: $notes")
        handler.postDelayed(stopRunnable, durationMs)
    }

    /** Stopping only ever follows a start that already passed the permission checks. */
    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(stopRunnable)
        val wasScanning = bleCallback != null || wifiReceiver != null
        bleCallback?.let { cb -> runCatching { bleScanner?.stopScan(cb) } }
        bleCallback = null
        wifiReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        wifiReceiver = null
        _scanning.value = false
        if (wasScanning) {
            DebugLog.log(TAG, "stop(): ble advertisements=$bleHits distinct=${seenBle.size}, wifi results=$wifiResults distinct ssids=${seenWifi.size}, cameras=${_cameras.value.map { it.ssid }}")
        }
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
            DebugLog.log(TAG, "ble: no adapter")
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!granted(permission)) {
            notes += "Bluetooth scan permission not granted"
            DebugLog.log(TAG, "ble: $permission not granted")
            return
        }
        if (!adapter.isEnabled) {
            notes += "Bluetooth is off"
            DebugLog.log(TAG, "ble: adapter off")
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
                DebugLog.log(TAG, "ble: onScanFailed($errorCode)")
                _notes.value = _notes.value + "Bluetooth scan failed ($errorCode)"
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            @SuppressLint("MissingPermission") // checked above for the current API level
            scanner.startScan(null, settings, cb)
            bleScanner = scanner
            bleCallback = cb
            DebugLog.log(TAG, "ble: scan started (low latency, no filters)")
        } catch (e: SecurityException) {
            DebugLog.log(TAG, "ble: startScan SecurityException", e)
            notes += "Bluetooth scan not permitted"
        }
    }

    @SuppressLint("MissingPermission") // device.name is wrapped in a SecurityException catch
    private fun onBle(result: ScanResult) {
        bleHits++
        val address = result.device?.address ?: return
        // The advertised name needs no BLUETOOTH_CONNECT permission, unlike device.name.
        var name = result.scanRecord?.deviceName?.trim()
        if (name.isNullOrEmpty()) {
            name = try { result.device?.name?.trim() } catch (e: SecurityException) { null }
        }
        if (seenBle.add(address)) {
            DebugLog.log(TAG, "ble: seen $address name=${name ?: "-"} rssi=${result.rssi} adv=${result.scanRecord?.bytes?.let { DebugLog.hex(it, it.size, 12) }}")
        }
        if (name.isNullOrEmpty()) return
        if (!CameraWifi.looksLikeCamera(name, namePrefixes)) return
        DebugLog.log(TAG, "ble: CAMERA $name at $address -> bssid ${CameraWifi.bssidFromBleAddress(address)}")
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
            DebugLog.log(TAG, "wifi: no WifiManager")
            return
        }
        // Scan results need fine location on every version; the system returns an empty
        // list (no exception) without it, or when location services are switched off.
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            notes += "Location permission not granted (Android needs it to list Wi-Fi networks)"
            DebugLog.log(TAG, "wifi: ACCESS_FINE_LOCATION not granted")
            return
        }
        if (!wm.isWifiEnabled) {
            notes += "Wi-Fi is off"
            DebugLog.log(TAG, "wifi: disabled")
            return
        }
        if (!locationServicesOn()) {
            notes += "Location services are off (Android needs them for Wi-Fi scanning)"
            DebugLog.log(TAG, "wifi: location services off")
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
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        DebugLog.log(TAG, "wifi: startScan() accepted=$started (false usually means Android's scan throttle)")
    }

    private fun readWifiResults(wm: WifiManager) {
        val results = try {
            wm.scanResults
        } catch (e: SecurityException) {
            DebugLog.log(TAG, "wifi: getScanResults SecurityException", e)
            return
        }
        wifiResults += results.size
        DebugLog.log(TAG, "wifi: ${results.size} scan results")
        for (r in results) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                r.wifiSsid?.toString()?.trim('"')
            } else {
                @Suppress("DEPRECATION") r.SSID
            }
            if (!ssid.isNullOrEmpty() && seenWifi.add(ssid)) {
                DebugLog.log(TAG, "wifi: ssid=\"$ssid\" bssid=${r.BSSID} rssi=${r.level} caps=${r.capabilities}")
            }
            if (!CameraWifi.looksLikeCamera(ssid, namePrefixes)) continue
            DebugLog.log(TAG, "wifi: CAMERA $ssid at ${r.BSSID}")
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
