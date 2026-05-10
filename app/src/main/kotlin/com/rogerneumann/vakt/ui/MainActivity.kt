package com.rogerneumann.vakt.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.vakt.databinding.ActivityMainBinding
import com.rogerneumann.vakt.service.OBD2ForegroundService
import com.rogerneumann.vakt.ui.history.HistoryActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var titleTapCount = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted/denied — user can still use demo mode */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRequiredPermissions()
        setupButtons()
        setupDemoEasterEgg()
        observeLiveData()
    }

    private fun requestRequiredPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun setupButtons() {
        binding.btnStartService.setOnClickListener {
            startForegroundService(Intent(this, OBD2ForegroundService::class.java))
        }

        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, OBD2ForegroundService::class.java))
            // Also stop demo if it was running standalone
            if (viewModel.isDemoMode) {
                viewModel.stopDemo()
                binding.tvDemoBadge.visibility = View.GONE
            }
        }

        binding.btnStopTrip.setOnClickListener {
            viewModel.stopTrip()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    /**
     * Hidden easter egg: tap the VAKT title 5 times to toggle Demo Mode.
     * Demo mode runs the synthetic data loop without needing a real dongle
     * or the foreground service — useful for UI validation on any device.
     */
    private fun setupDemoEasterEgg() {
        binding.tvTitle.setOnClickListener {
            titleTapCount++
            if (titleTapCount >= 5) {
                titleTapCount = 0
                if (viewModel.isDemoMode) {
                    viewModel.stopDemo()
                    binding.tvDemoBadge.visibility = View.GONE
                } else {
                    viewModel.startDemo()
                    binding.tvDemoBadge.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Collect liveData from the ViewModel and push to the DashboardView.
     * Works for both real telemetry (service running) and demo mode.
     */
    private fun observeLiveData() {
        lifecycleScope.launch {
            viewModel.liveData.collectLatest { data ->
                binding.dashboardView.updateData(data)
            }
        }
    }
}
