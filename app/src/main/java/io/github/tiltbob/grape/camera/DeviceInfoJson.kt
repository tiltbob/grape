package io.github.tiltbob.grape.camera

import org.json.JSONObject

/** Serialises [DeviceInfo] for passing between activities. */
object DeviceInfoJson {
    fun encode(info: DeviceInfo): String = JSONObject().apply {
        put("host", info.host)
        put("protocol", info.protocol.name)
        put("model", info.model)
        put("firmware", info.firmware)
        put("ssid", info.ssid)
        put("brand", info.brand)
        put("floatAngle", info.floatAngle)
        put("rotateAngle", info.rotateAngle)
        put("rawInfo", info.rawInfo)
    }.toString()

    fun decode(text: String): DeviceInfo {
        val j = JSONObject(text)
        return DeviceInfo(
            host = j.getString("host"),
            protocol = CameraProtocol.valueOf(j.optString("protocol", CameraProtocol.TUBE.name)),
            model = j.optString("model").ifBlank { null },
            firmware = j.optString("firmware").ifBlank { null },
            ssid = j.optString("ssid").ifBlank { null },
            brand = j.optString("brand").ifBlank { null },
            floatAngle = j.optBoolean("floatAngle", false),
            rotateAngle = j.optInt("rotateAngle", 0),
            rawInfo = j.optString("rawInfo").ifBlank { null },
        )
    }
}
