package com.rogerneumann.vakt.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import android.Manifest
import android.content.pm.PackageManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var lightingManager: LightingManager
    @Inject lateinit var profileHub: VehicleProfileHub
    @Inject lateinit var profileManager: VehicleProfileManager

    private var titleTapCount = 0
    private var hamburgerPulseAnimator: ObjectAnimator? = null
    private var hamburgerDotView: View? = null

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
        observeLiveData()
        FirstRunWizardManager(this, sharedPreferences, profileHub, profileManager) {
            // Callback: wizard was skipped or completed without pairing
            startHamburgerPulse()
        }.showIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        showSwipeHintIfNeeded()
        // Clear the hamburger dot if a device is now paired
        if (sharedPreferences.getString("saved_device_address", null) != null) {
            removeHamburgerDot()
        }
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
            // Stop any active pulse animation on first tap
            hamburgerPulseAnimator?.cancel()
            hamburgerPulseAnimator = null
            showHamburgerDot()

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

    // ── Block 14c: Swipe hint overlay ────────────────────────────────────────

    /**
     * On first launch after the wizard (seen_swipe_hint == false):
     * shows a semi-transparent overlay with left/right arrow hints.
     * Dismissed on any touch.
     */
    private fun showSwipeHintIfNeeded() {
        val prefs = getSharedPreferences("vakt_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seen_swipe_hint", false)) return

        val root = binding.root as? ViewGroup ?: return

        // Container overlay (semi-transparent dark)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(51, 0, 0, 0))  // 20% opacity
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val leftArrow = TextView(this).apply {
            text = "◀ Data"
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                lp.marginStart = dpToPx(24)
            }
        }

        val rightArrow = TextView(this).apply {
            text = "Music ▶"
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                lp.marginEnd = dpToPx(24)
            }
        }

        overlay.addView(leftArrow)
        overlay.addView(rightArrow)

        overlay.setOnClickListener {
            root.removeView(overlay)
            prefs.edit().putBoolean("seen_swipe_hint", true).apply()
        }

        root.addView(overlay)
    }

    // ── Block 14d: Hamburger pulse + red dot ─────────────────────────────────

    /**
     * Starts a 20-second pulse animation on the hamburger button.
     * Called after wizard skip or when no device is paired on first launch.
     * After 20 s (or on first tap): stops pulse, shows red dot badge.
     */
    fun startHamburgerPulse() {
        val hamburger = binding.btnHamburger
        // Make the hamburger visible so the pulse is noticeable
        hamburger.animate().alpha(1f).setDuration(200).start()

        hamburgerPulseAnimator?.cancel()
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            hamburger,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.15f, 1.0f)
        ).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        animator.start()
        hamburgerPulseAnimator = animator

        // After 20 seconds: stop pulse, show red dot
        Handler(Looper.getMainLooper()).postDelayed({
            if (hamburgerPulseAnimator === animator) {
                animator.cancel()
                hamburgerPulseAnimator = null
                showHamburgerDot()
            }
        }, 20_000L)
    }

    private fun showHamburgerDot() {
        // Don't add a dot if device is already paired
        if (sharedPreferences.getString("saved_device_address", null) != null) return
        // Don't add duplicate dots
        if (hamburgerDotView != null) return

        val hamburger = binding.btnHamburger
        val container = hamburger.parent as? ViewGroup ?: return

        val dotSizePx = dpToPx(12)
        val dot = View(this).apply {
            setBackgroundColor(Color.RED)
            layoutParams = FrameLayout.LayoutParams(dotSizePx, dotSizePx).also { lp ->
                lp.gravity = Gravity.TOP or Gravity.START
                // Position dot at top-right of hamburger button
                lp.topMargin   = hamburger.top + dpToPx(2)
                lp.marginStart = hamburger.right - dotSizePx - dpToPx(2)
            }
        }
        container.addView(dot)
        hamburgerDotView = dot
    }

    private fun removeHamburgerDot() {
        val dot = hamburgerDotView ?: return
        (dot.parent as? ViewGroup)?.removeView(dot)
        hamburgerDotView = null
        hamburgerPulseAnimator?.cancel()
        hamburgerPulseAnimator = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
