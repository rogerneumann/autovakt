package com.rogerneumann.autovakt.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rogerneumann.autovakt.data.VehicleProfileHub
import com.rogerneumann.autovakt.data.VehicleProfileManager
import com.rogerneumann.autovakt.ui.scan.DeviceScanFragment

/**
 * Manages the first-run wizard flow (Block 14d).
 *
 * 3-step flow:
 *   Step 1 — Bluetooth permissions (non-skippable)
 *   Step 2 — Notification access (skippable)
 *   Step 3 — Connect OBD adapter (skippable)
 *
 * [onWizardSkipped] is called when the user skips step 3 without pairing a device,
 * so that MainActivity can start the hamburger pulse animation.
 */
class FirstRunWizardManager(
    private val activity: FragmentActivity,
    private val prefs: SharedPreferences,
    private val profileHub: VehicleProfileHub,
    private val profileManager: VehicleProfileManager,
    private val onWizardSkipped: () -> Unit = {}
) {
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    fun showIfNeeded() {
        if (prefs.contains("saved_device_address")) return
        // wizard_completed is set as soon as the user moves past step 1, so Activity
        // recreates (e.g. returning from notification settings) don't restart the whole flow.
        if (prefs.getBoolean("wizard_completed", false)) return
        registerPermissionLauncher()
        showBluetoothStep()
    }

    /** Register before onStart — must be called during onCreate before show. */
    private fun registerPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                showNotificationStep()
            } else {
                showBluetoothDeniedDialog()
            }
        }
    }

    // ── Step 1: Bluetooth permissions (non-skippable) ─────────────────────────

    private fun showBluetoothStep() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val explanation = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            "Android requires location permission to scan for Bluetooth devices. " +
                "AutoVakt does not store your location.\n\n" +
                "Bluetooth access is also required to connect your OBD adapter."
        } else {
            "Bluetooth access is required to connect your OBD adapter."
        }

        // Check if all permissions already granted
        val allGranted = permissions.all { perm ->
            ContextCompat.checkSelfPermission(activity, perm) == PermissionChecker.PERMISSION_GRANTED
        }
        if (allGranted) {
            showNotificationStep()
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Welcome to AutoVakt")
            .setMessage(explanation)
            .setPositiveButton("Grant permissions") { _, _ ->
                permissionLauncher?.launch(permissions)
            }
            .setCancelable(false)
            .show()
    }

    private fun showBluetoothDeniedDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Bluetooth Required")
            .setMessage(
                "Bluetooth is required to connect your OBD adapter. " +
                "Please grant the permission in Settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", activity.packageName, null)
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Step 2: Notification access (skippable) ───────────────────────────────

    private fun showNotificationStep() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Show What's Playing")
            .setMessage(
                "To show what's playing in your media views, allow Notification Access."
            )
            .setPositiveButton("Allow") { _, _ ->
                // Mark completed before leaving so Activity recreate doesn't restart wizard
                prefs.edit().putBoolean("wizard_completed", true).apply()
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                showAdapterStep()
            }
            .setNegativeButton("Skip for now") { _, _ ->
                prefs.edit().putBoolean("wizard_completed", true).apply()
                showAdapterStep()
            }
            .setCancelable(false)
            .show()
    }

    // ── Step 3: Connect OBD adapter (skippable) ───────────────────────────────

    private fun showAdapterStep() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Connect Your OBD Adapter")
            .setMessage(
                "Connect your OBD2 adapter to get started.\n\n" +
                "We recommend the OBDLink CX (BLE) or MX+ (Bluetooth Classic)."
            )
            .setPositiveButton("Scan for adapters") { _, _ ->
                prefs.edit().putBoolean("wizard_completed", true).apply()
                DeviceScanFragment().show(activity.supportFragmentManager, "device_scan")
            }
            .setNegativeButton("Skip for now") { _, _ ->
                prefs.edit().putBoolean("wizard_completed", true).apply()
                onWizardSkipped()
            }
            .setCancelable(false)
            .show()
    }
}
