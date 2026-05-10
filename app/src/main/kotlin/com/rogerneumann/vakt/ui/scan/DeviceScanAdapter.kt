package com.rogerneumann.vakt.ui.scan

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rogerneumann.vakt.databinding.ItemDeviceScanBinding

class DeviceScanAdapter : ListAdapter<ScannedDevice, DeviceScanAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    var onDeviceSelected: (ScannedDevice) -> Unit = {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceScanBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(private val binding: ItemDeviceScanBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: ScannedDevice) {
            binding.tvDeviceName.text = device.name
            binding.tvDeviceAddress.text = device.address
            binding.tvRssi.text = "RSSI: ${device.rssi} dBm"

            // Set type badge
            binding.tvDeviceType.text = device.type.name
            binding.tvDeviceType.setBackgroundColor(
                when (device.type) {
                    DeviceType.BLE -> Color.parseColor("#4CAF50")      // Green for BLE
                    DeviceType.CLASSIC -> Color.parseColor("#2196F3")  // Blue for Classic
                }
            )

            // Set signal strength color based on RSSI
            val rssiColor = when {
                device.rssi >= -50 -> Color.parseColor("#4CAF50")      // Green (strong)
                device.rssi >= -70 -> Color.parseColor("#FFC107")      // Amber (medium)
                else -> Color.parseColor("#F44336")                     // Red (weak)
            }
            binding.tvRssi.setTextColor(rssiColor)

            binding.root.setOnClickListener {
                onDeviceSelected(device)
            }
        }
    }

    private class DeviceDiffCallback : DiffUtil.ItemCallback<ScannedDevice>() {
        override fun areItemsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean {
            return oldItem == newItem
        }
    }
}
