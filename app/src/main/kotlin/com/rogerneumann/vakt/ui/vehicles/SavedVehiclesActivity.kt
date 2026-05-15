package com.rogerneumann.vakt.ui.vehicles

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rogerneumann.vakt.R
import com.rogerneumann.vakt.data.SavedVehicle
import com.rogerneumann.vakt.data.VehicleLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SavedVehiclesActivity : AppCompatActivity() {

    @Inject
    lateinit var vehicleLayoutManager: VehicleLayoutManager

    private lateinit var rvSavedVehicles: RecyclerView
    private lateinit var tvEmptyState: View
    private lateinit var layoutSelectActions: View
    private lateinit var btnSelectAll: android.widget.Button
    private lateinit var btnDeleteSelected: android.widget.Button
    private lateinit var adapter: SavedVehiclesAdapter
    private var allVehicles: List<SavedVehicle> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_vehicles)

        rvSavedVehicles = findViewById(R.id.rvSavedVehicles)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        layoutSelectActions = findViewById(R.id.layoutSelectActions)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        setupRecyclerView()
        setupButtons()
        refreshList()
    }

    private fun setupRecyclerView() {
        adapter = SavedVehiclesAdapter()
        rvSavedVehicles.layoutManager = LinearLayoutManager(this)
        rvSavedVehicles.adapter = adapter

        adapter.onRename = { vehicle ->
            showRenameDialog(vehicle)
        }

        adapter.onDelete = { vehicle ->
            showDeleteDialog(vehicle)
        }

        adapter.onLongClick = { _vehicle ->
            enterSelectMode()
        }
    }

    private fun setupButtons() {
        btnSelectAll.setOnClickListener {
            adapter.selectAll(allVehicles)
        }

        btnDeleteSelected.setOnClickListener {
            showBulkDeleteDialog()
        }
    }

    private fun enterSelectMode() {
        adapter.isSelectMode = true
        layoutSelectActions.visibility = View.VISIBLE
    }

    private fun exitSelectMode() {
        adapter.isSelectMode = false
        adapter.clearSelection()
        layoutSelectActions.visibility = View.GONE
    }

    private fun showRenameDialog(vehicle: SavedVehicle) {
        val input = EditText(this).apply {
            setText(vehicle.userLabel ?: vehicle.autoLabel)
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Vehicle")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = input.text.toString().trim()
                vehicleLayoutManager.setUserLabel(vehicle.key, if (newLabel.isEmpty()) null else newLabel)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(vehicle: SavedVehicle) {
        // Dialog 1: Confirm removal
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Vehicle")
            .setMessage("Remove ${vehicle.displayLabel}?")
            .setPositiveButton("Remove") { _, _ ->
                // Dialog 2: Cannot be undone warning
                MaterialAlertDialogBuilder(this)
                    .setTitle("Confirm")
                    .setMessage("This action cannot be undone.")
                    .setPositiveButton("OK") { _, _ ->
                        vehicleLayoutManager.deleteVehicle(vehicle.key)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBulkDeleteDialog() {
        val count = adapter.selectedKeys.size
        // Dialog 1: Confirm removal
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Vehicles")
            .setMessage("Remove ${count} vehicle(s)?")
            .setPositiveButton("Remove") { _, _ ->
                // Dialog 2: Cannot be undone warning
                MaterialAlertDialogBuilder(this)
                    .setTitle("Confirm")
                    .setMessage("This action cannot be undone.")
                    .setPositiveButton("OK") { _, _ ->
                        adapter.selectedKeys.forEach { key ->
                            vehicleLayoutManager.deleteVehicle(key)
                        }
                        exitSelectMode()
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        allVehicles = vehicleLayoutManager.getSavedVehicles()
        adapter.submitList(allVehicles)

        if (allVehicles.isEmpty()) {
            rvSavedVehicles.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
        } else {
            rvSavedVehicles.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
        }

        if (adapter.isSelectMode && adapter.selectedKeys.isEmpty()) {
            exitSelectMode()
        }
    }
}
