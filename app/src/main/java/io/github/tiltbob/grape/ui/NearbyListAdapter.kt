package io.github.tiltbob.grape.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.databinding.ItemNearbyBinding
import io.github.tiltbob.grape.net.CameraWifi
import io.github.tiltbob.grape.net.NearbyCamera

class NearbyListAdapter(
    private val onConnect: (NearbyCamera) -> Unit,
) : RecyclerView.Adapter<NearbyListAdapter.Holder>() {

    private val items = mutableListOf<NearbyCamera>()
    var lastUsedSsid: String? = null

    fun submit(list: List<NearbyCamera>) {
        items.clear()
        items.addAll(list)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemNearbyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemNearbyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val cam = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvName.text = cam.ssid
        val radios = listOfNotNull(
            if (cam.seenByBluetooth) ctx.getString(R.string.seen_bluetooth) else null,
            if (cam.seenByWifi) ctx.getString(R.string.seen_wifi) else null,
        ).joinToString(", ")
        val security = when (cam.security) {
            CameraWifi.Security.OPEN -> ctx.getString(R.string.security_open)
            CameraWifi.Security.WPA2 -> ctx.getString(R.string.security_wpa2)
            CameraWifi.Security.WPA3 -> ctx.getString(R.string.security_wpa3)
        }
        holder.binding.tvDetails.text = listOfNotNull(
            radios.ifBlank { null },
            security,
            cam.rssi?.let { "$it dBm" },
            if (cam.ssid.equals(lastUsedSsid, ignoreCase = true)) ctx.getString(R.string.nearby_last_used) else null,
        ).joinToString(" · ")
        holder.itemView.setOnClickListener { onConnect(cam) }
        holder.binding.btnConnect.setOnClickListener { onConnect(cam) }
    }
}
