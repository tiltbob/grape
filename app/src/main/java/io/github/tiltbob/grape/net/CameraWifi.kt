package io.github.tiltbob.grape.net

/**
 * Pure helpers describing how Bebird scopes advertise themselves, mirroring the vendor app.
 * Kept free of Android classes so they can be unit-tested.
 */
object CameraWifi {
    /** Advertised names (BLE device name == Wi-Fi SSID) start with one of these. */
    val DEFAULT_NAME_PREFIXES = listOf("bebird", "xlife")

    /** The only passphrase the vendor app ever uses, for the few models with a secured AP. */
    const val DEFAULT_PASSPHRASE = "12345678"

    /** Model tokens (second part of `brand-Model-1234`) whose access point is WPA protected. */
    private val SECURED_MODELS = setOf("w3", "e3", "se", "max22")
    private val SECURED_MODEL_PREFIXES = listOf("elite14")

    fun looksLikeCamera(name: String?, prefixes: List<String> = DEFAULT_NAME_PREFIXES): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return prefixes.any { it.isNotBlank() && n.startsWith(it.lowercase()) }
    }

    /**
     * The scope's Wi-Fi BSSID is its BLE address with the last byte decremented by two
     * (saturating at zero). Input and output are `AA:BB:CC:DD:EE:FF` strings.
     */
    fun bssidFromBleAddress(bleAddress: String): String? {
        val bytes = parseMac(bleAddress) ?: return null
        val last = (bytes[5].toInt() and 0xFF) - 2
        bytes[5] = last.coerceIn(0, 255).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun parseMac(text: String): ByteArray? {
        val parts = text.trim().split(':', '-')
        if (parts.size != 6) return null
        val out = ByteArray(6)
        for (i in 0 until 6) {
            val v = parts[i].toIntOrNull(16) ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    /**
     * Whether an SSID belongs to a model whose AP needs [DEFAULT_PASSPHRASE]. The vendor app
     * looks at the token after the first `-` (or `_`), e.g. `bebird-W3-1234`.
     */
    fun isSecuredBySsid(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        val model = ssid.split('-').getOrNull(1)?.ifBlank { null }
            ?: ssid.split('_').getOrNull(1)?.ifBlank { null }
            ?: return false
        val m = model.replace(" ", "").lowercase()
        return m in SECURED_MODELS || SECURED_MODEL_PREFIXES.any { m.startsWith(it) }
    }

    enum class Security { OPEN, WPA2, WPA3 }

    /** Pick the security from Wi-Fi scan capabilities when known, else from the SSID. */
    fun securityFor(ssid: String?, capabilities: String?): Security {
        val caps = capabilities?.lowercase()
        if (!caps.isNullOrBlank()) {
            return when {
                caps.contains("wpa3") || caps.contains("sae") -> Security.WPA3
                caps.contains("wpa2") || caps.contains("wpa") || caps.contains("psk") -> Security.WPA2
                else -> Security.OPEN
            }
        }
        return if (isSecuredBySsid(ssid)) Security.WPA2 else Security.OPEN
    }
}
