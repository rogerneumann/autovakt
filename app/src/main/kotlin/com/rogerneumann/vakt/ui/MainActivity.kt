package com.rogerneumann.vakt.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.vakt.R
import com.rogerneumann.vakt.data.LightingManager
import com.rogerneumann.vakt.data.VehicleLayoutManager
import com.rogerneumann.vakt.data.VehicleProfileHub
import com.rogerneumann.vakt.data.VehicleProfileManager
import com.rogerneumann.vakt.databinding.ActivityMainBinding
import com.rogerneumann.vakt.obd2.ConnectionState
import com.rogerneumann.vakt.service.OBD2ForegroundService
import com.rogerneumann.vakt.ui.history.HistoryActivity
import com.rogerneumann.vakt.ui.scan.DeviceScanFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var lightingManager: LightingManager
    @Inject lateinit var profileHub: VehicleProfileHub
    @Inject lateinit var profileManager: VehicleProfileManager
    @Inject lateinit var vehicleLayoutManager: VehicleLayoutManager

    private var titleTapCount = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted/denied — demo mode always available */ }

    // Auto-hide hamburger after 3 seconds of idle
    private val hideHamburgerRunnable = Runnable {
        binding.btnHamburger.animate().alpha(0f).setDuration(400).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()
        requestRequiredPermissions()
        setupDrawer()
        setupHamburgerReveal()
        setupDemoEasterEgg()
        binding.dashboardView.vehicleLayoutManager = vehicleLayoutManager
        observeLiveData()
        FirstRunWizardManager(this, sharedPreferences, profileHub, profileManager).showIfNeeded()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Prevent edge-swipe from conflicting with system gesture navigation
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private fun setupDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawers()
            when (item.itemId) {
                R.id.nav_connect    -> onConnectClicked()
                R.id.nav_disconnect -> onDisconnectClicked()
                R.id.nav_stop_trip  -> viewModel.stopTrip()
                R.id.nav_settings   -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_history    -> startActivity(Intent(this, HistoryActivity::class.java))
                R.id.nav_demo       -> toggleDemoMode()
            }
            true
        }
    }

    private fun setupHamburgerReveal() {
        binding.dashboardView.setOnClickListener { showHamburger() }
        binding.btnHamburger.setOnClickListener {
            cancelHamburgerHide()
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                // Re-lock after drawer closes so edge swipe doesn't conflict with gestures
                binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                binding.btnHamburger.animate().alpha(0f).setDuration(300).start()
            }
        })
    }

    private fun showHamburger() {
        cancelHamburgerHide()
        binding.btnHamburger.animate().alpha(1f).setDuration(200).start()
        binding.btnHamburger.postDelayed(hideHamburgerRunnable, 3000)
    }

    private fun cancelHamburgerHide() {
        binding.btnHamburger.removeCallbacks(hideHamburgerRunnable)
    }

    // Hidden easter egg: tap "VAKT" in the nav drawer header 5 times to toggle Demo Mode
    private fun setupDemoEasterEgg() {
        val header = binding.navigationView.getHeaderView(0)
        header.findViewById<TextView>(R.id.navHeaderTitle).setOnClickListener {
            titleTapCount++
            if (titleTapCount >= 5) {
                titleTapCount = 0
                toggleDemoMode()
            }
        }
    }

    private fun toggleDemoMode() {
        val menu = binding.navigationView.menu
        if (viewModel.isDemoMode) {
            viewModel.stopDemo()
            binding.tvDemoBadge.visibility = View.GONE
            menu.findItem(R.id.nav_demo).isVisible = false
        } else {
            viewModel.startDemo()
            binding.tvDemoBadge.visibility = View.VISIBLE
            menu.findItem(R.id.nav_demo).isVisible = true
        }
    }

    private fun observeLiveData() {
        lifecycleScope.launch {
            viewModel.liveData.collectLatest { data ->
                binding.dashboardView.updateData(data)
                updateDrawerState(data.connectionState)
                updatePreConnectionDim(data)
                updateNavHeaderVehicle(data)
            }
        }
        lifecycleScope.launch {
            lightingManager.theme.collectLatest { theme ->
                binding.dashboardView.theme = theme
            }
        }
    }

    private fun updateDrawerState(state: ConnectionState) {
        val menu = binding.navigationView.menu
        val isConnected = state is ConnectionState.Connected
        menu.findItem(R.id.nav_connect).isVisible    = !isConnected
        menu.findItem(R.id.nav_disconnect).isVisible = isConnected
    }

    private fun updatePreConnectionDim(data: com.rogerneumann.vakt.data.VaktLiveData) {
        val isConnected = data.connectionState is ConnectionState.Connected
        val hasSavedDevice = sharedPreferences.contains("saved_device_address")
        val showDim = !isConnected && !hasSavedDevice
        binding.viewDimScrim.visibility = if (showDim) View.VISIBLE else View.GONE
    }

    private fun updateNavHeaderVehicle(data: com.rogerneumann.vakt.data.VaktLiveData) {
        val header = binding.navigationView.getHeaderView(0)
        val subtitle = header.findViewById<TextView>(R.id.navHeaderSubtitle)
        val profile = data.vehicleProfile
        val state = data.connectionState  // local val enables smart-cast in when branches
        subtitle.text = when {
            profile.make != null && profile.model != null -> "${profile.make} ${profile.model}"
            data.vin != null                              -> "VIN: ${data.vin}"
            state is ConnectionState.Connected  -> "Connected"
            state is ConnectionState.Connecting -> "Connecting…"
            state is ConnectionState.Error      -> "Error: ${state.message}"
            else                                -> "Not connected"
        }
    }

    private fun onConnectClicked() {
        val savedAddress = sharedPreferences.getString("saved_device_address", null)
        val savedType    = sharedPreferences.getString("saved_device_type", null)
        if (savedAddress != null && savedType != null) {
            Toast.makeText(this, "Connecting to saved device…", Toast.LENGTH_SHORT).show()
            startForegroundService(Intent(this, OBD2ForegroundService::class.java))
        } else {
            DeviceScanFragment().show(supportFragmentManager, "device_scan")
        }
    }

    private fun onDisconnectClicked() {
        stopService(Intent(this, OBD2ForegroundService::class.java))
        if (viewModel.isDemoMode) {
            viewModel.stopDemo()
            binding.tvDemoBadge.visibility = View.GONE
            binding.navigationView.menu.findItem(R.id.nav_demo).isVisible = false
        }
    }

    private fun requestRequiredPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: BLUETOOTH_SCAN declared neverForLocation — no location dialog needed
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                // API 26–30: BLE/Classic scanning requires location permission
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
