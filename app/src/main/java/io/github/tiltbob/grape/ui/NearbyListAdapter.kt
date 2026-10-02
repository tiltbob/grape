package io.github.tiltbob.grape.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.databinding.ItemNearbyBinding
import io.github.tiltbob.grape.net.CameraWifi
import io.github.tiltbob.grape.net.NearbyCamera

class NearbyListAdapter(
    private val onConnect: (NearbyCamera) -> Unit,
    /** Long press on a remembered scope. */
    private val onForget: (NearbyCamera) -> Unit,
) : RecyclerView.Adapter<NearbyListAdapter.Holder>() {

    private val items = mutableListOf<NearbyCamera>()

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
        val lastUsed = cam.lastJoinedMs?.takeIf { it > 0 }?.let {
            ctx.getString(
                R.string.nearby_last_used_at,
                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
        holder.binding.tvDetails.text = listOfNotNull(
            if (cam.heard) radios.ifBlank { null } else ctx.getString(R.string.nearby_remembered_tag),
            security,
            cam.rssi?.takeIf { cam.heard }?.let { "$it dBm" },
            lastUsed,
        ).joinToString(" · ")
        holder.itemView.alpha = if (cam.heard) 1f else 0.7f
        holder.itemView.setOnClickListener { onConnect(cam) }
        holder.binding.btnConnect.setOnClickListener { onConnect(cam) }
        holder.itemView.setOnLongClickListener {
            if (cam.remembered) {
                onForget(cam)
                true
            } else {
                false
            }
        }
    }
}
