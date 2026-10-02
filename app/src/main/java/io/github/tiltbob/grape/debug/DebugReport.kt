package io.github.tiltbob.grape.debug

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.tiltbob.grape.BuildConfig
import io.github.tiltbob.grape.net.NetworkLink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Assembles the shareable debug report: environment summary plus the [DebugLog]. */
object DebugReport {

    fun build(context: Context, link: NetworkLink): String {
        val sb = StringBuilder()
        sb.appendLine("EarDigger debug report")
        sb.appendLine("generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        sb.appendLine("app: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("permissions:")
        for (p in permissionsOfInterest()) {
            val granted = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            sb.appendLine("  ${p.removePrefix("android.permission.")}: ${if (granted) "granted" else "DENIED"}")
        }
        sb.appendLine()
        sb.appendLine("radios:")
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        sb.appendLine("  wifi enabled: ${wifi?.isWifiEnabled}")
        val bt = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        sb.appendLine("  bluetooth: ${if (bt == null) "no adapter" else if (bt.isEnabled) "on" else "off"}")
        sb.appendLine("  location services: ${locationOn(context)}")
        sb.appendLine()
        sb.appendLine("network link: status=${link.status.value} target=${link.targetSsid} local=${link.localAddress()} candidates=${link.candidateHosts()}")
        sb.appendLine(link.describeNetwork())
        sb.appendLine()
        sb.appendLine("log (${DebugLog.size()} lines):")
        sb.append(DebugLog.dump())
        sb.appendLine()
        return sb.toString()
    }

    private fun permissionsOfInterest(): List<String> = buildList {
        add(Manifest.permission.INTERNET)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        add(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun locationOn(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled.toString()
        } else {
            @Suppress("DEPRECATION")
            (Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0).toString()
        }
    } catch (e: Exception) {
        "unknown (${e.message})"
    }

    /** Writes the report to the app cache and opens the system share sheet with it. */
    fun share(context: Context, link: NetworkLink) {
        val text = build(context, link)
        val dir = File(context.cacheDir, "debug").apply { mkdirs() }
        val file = File(dir, "eardigger-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(context: Context, link: NetworkLink) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("EarDigger debug log", build(context, link)))
    }
}
