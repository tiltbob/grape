package io.github.tiltbob.grape.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.tiltbob.grape.R
import io.github.tiltbob.grape.camera.CameraProtocol
import io.github.tiltbob.grape.camera.DeviceInfo
import io.github.tiltbob.grape.databinding.ItemCameraBinding

class CameraListAdapter(
    private val onClick: (DeviceInfo) -> Unit,
) : RecyclerView.Adapter<CameraListAdapter.Holder>() {

    private val items = mutableListOf<DeviceInfo>()

    fun submit(list: List<DeviceInfo>) {
        items.clear()
        items.addAll(list)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemCameraBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val info = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvName.text = info.displayName
        val protocol = when (info.protocol) {
            CameraProtocol.TUBE -> ctx.getString(R.string.protocol_tube)
            CameraProtocol.ML -> ctx.getString(R.string.protocol_ml)
        }
        holder.binding.tvDetails.text = listOfNotNull(
            info.host,
            info.firmware?.let { "firmware $it" },
            info.ssid,
            protocol,
        ).joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(info) }
    }
}
