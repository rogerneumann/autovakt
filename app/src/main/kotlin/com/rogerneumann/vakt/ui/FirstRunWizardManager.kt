package com.rogerneumann.vakt.ui

import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rogerneumann.vakt.data.VehicleProfileHub
import com.rogerneumann.vakt.data.VehicleProfileManager
import com.rogerneumann.vakt.ui.scan.DeviceScanFragment

class FirstRunWizardManager(
    private val activity: FragmentActivity,
    private val prefs: SharedPreferences,
    private val profileHub: VehicleProfileHub,
    private val profileManager: VehicleProfileManager
) {
    fun showIfNeeded() {
        if (prefs.contains("saved_device_address")) return
        showProfileStep()
    }

    private fun showProfileStep() {
        val profiles = profileHub.getAvailableProfiles()
        val labels = (listOf("Auto (VIN detection)") +
                profiles.map { "${it.make} ${it.model} (${it.year})" }).toTypedArray()
        var selectedIndex = 0

        MaterialAlertDialogBuilder(activity)
            .setTitle("Welcome to Vakt")
            .setSingleChoiceItems(labels, 0) { _, which -> selectedIndex = which }
            .setPositiveButton("Next") { _, _ ->
                if (selectedIndex == 0) {
                    profileManager.setActiveProfile("auto")
                } else {
                    profileManager.setActiveProfile(profiles[selectedIndex - 1].id)
                }
                showScanStep()
            }
            .setNegativeButton("Skip", null)
            .setCancelable(false)
            .show()
    }

    private fun showScanStep() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Connect OBD Adapter")
            .setMessage("Tap Scan to find your OBD-II Bluetooth adapter. You can also connect later from the menu.")
            .setPositiveButton("Scan for Devices") { _, _ ->
                DeviceScanFragment().show(activity.supportFragmentManager, "device_scan")
            }
            .setNegativeButton("Skip", null)
            .show()
    }
}
