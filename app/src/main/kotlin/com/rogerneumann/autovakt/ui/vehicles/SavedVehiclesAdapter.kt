package com.rogerneumann.autovakt.ui.vehicles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import com.rogerneumann.autovakt.R
import com.rogerneumann.autovakt.data.SavedVehicle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedVehiclesAdapter : ListAdapter<SavedVehicle, SavedVehiclesAdapter.VH>(DiffCallback) {

    var onRename: (SavedVehicle) -> Unit = {}
    var onDelete: (SavedVehicle) -> Unit = {}
    var onLongClick: (SavedVehicle) -> Unit = {}

    private var _isSelectMode = false
    val selectedKeys: MutableSet<String> = mutableSetOf()

    var isSelectMode: Boolean
        get() = _isSelectMode
        set(value) {
            _isSelectMode = value
            notifyDataSetChanged()
        }

    fun selectAll(vehicles: List<SavedVehicle>) {
        selectedKeys.clear()
        selectedKeys.addAll(vehicles.map { it.key })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedKeys.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_vehicle, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val vehicle = getItem(position)
        holder.bind(vehicle)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvVehicleName: TextView = itemView.findViewById(R.id.tvVehicleName)
        private val tvLastConnected: TextView = itemView.findViewById(R.id.tvLastConnected)
        private val checkVehicle: CheckBox = itemView.findViewById(R.id.checkVehicle)
        private val btnRename: ImageButton = itemView.findViewById(R.id.btnRename)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(vehicle: SavedVehicle) {
            tvVehicleName.text = vehicle.displayLabel
            tvLastConnected.text = "Last connected: ${formatDate(vehicle.lastConnected)}"

            checkVehicle.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            checkVehicle.isChecked = selectedKeys.contains(vehicle.key)

            btnRename.setOnClickListener {
                onRename(vehicle)
            }

            btnDelete.setOnClickListener {
                onDelete(vehicle)
            }

            itemView.setOnLongClickListener {
                onLongClick(vehicle)
                true
            }

            itemView.setOnClickListener {
                if (isSelectMode) {
                    val wasSelected = selectedKeys.contains(vehicle.key)
                    if (wasSelected) {
                        selectedKeys.remove(vehicle.key)
                    } else {
                        selectedKeys.add(vehicle.key)
                    }
                    checkVehicle.isChecked = !wasSelected
                }
            }
        }

        private fun formatDate(epochMs: Long): String {
            val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            return sdf.format(Date(epochMs))
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<SavedVehicle>() {
            override fun areItemsTheSame(oldItem: SavedVehicle, newItem: SavedVehicle): Boolean {
                return oldItem.key == newItem.key
            }

            override fun areContentsTheSame(oldItem: SavedVehicle, newItem: SavedVehicle): Boolean {
                return oldItem == newItem
            }
        }
    }
}
