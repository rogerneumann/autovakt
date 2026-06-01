package com.rogerneumann.autovakt.ui

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rogerneumann.autovakt.R
import com.rogerneumann.autovakt.data.LightingManager
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.data.VehicleProfileHub
import com.rogerneumann.autovakt.data.VehicleProfileManager
import com.rogerneumann.autovakt.databinding.ActivityMainBinding
import com.rogerneumann.autovakt.obd2.ConnectionState
import com.rogerneumann.autovakt.service.OBD2ForegroundService
import com.rogerneumann.autovakt.ui.history.HistoryActivity
import com.rogerneumann.autovakt.ui.scan.DeviceScanFragment
import com.rogerneumann.autovakt.BuildConfig
import com.rogerneumann.autovakt.media.MediaRemoteManager
import com.rogerneumann.autovakt.util.LogShareManager
import com.rogerneumann.autovakt.util.UpdateChecker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.view.WindowManager
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
    @Inject lateinit var logShareManager: LogShareManager
    @Inject lateinit var mediaRemoteManager: MediaRemoteManager

    private var titleTapCount = 0
    private var hamburgerPulseAnimator: ObjectAnimator? = null
    private var hamburgerDotView: View? = null

    // Diagnostics tap counter: 5 taps on the hamburger within 2 s → share logs
    private var diagTapCount = 0
    private var diagWindowEndMs = 0L
    private val openDrawerRunnable = Runnable {
        diagTapCount = 0
        diagWindowEndMs = 0L
        hamburgerPulseAnimator?.cancel()
        hamburgerPulseAnimator = null
        showHamburgerDot()
        cancelHamburgerHide()
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

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
        setupNavVersion()
        setupDemoEasterEgg()
        binding.dashboardView.vehicleLayoutManager = vehicleLayoutManager
        binding.dashboardView.mediaRemoteManager   = mediaRemoteManager
        binding.dashboardView.onLaunchMediaApp     = { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }
        observeLiveData()
        FirstRunWizardManager(this, sharedPreferences, profileHub, profileManager) {
            // Callback: wizard was skipped or completed without pairing
            startHamburgerPulse()
        }.showIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyScreenWakeSetting()
        showSwipeHintIfNeeded()
        val hasPairedDevice = sharedPreferences.getString("saved_device_address", null) != null
        if (hasPairedDevice) {
            removeHamburgerDot()
        } else if (sharedPreferences.getBoolean("wizard_completed", false)
            && hamburgerPulseAnimator == null && hamburgerDotView == null) {
            startHamburgerPulse()
        }
        promptPendingCrashReportIfNeeded()
        val layoutKey = viewModel.currentLayoutKey.value
        binding.dashboardView.gaugeLayout = vehicleLayoutManager.getLayout(layoutKey, this, isAA = false)
        binding.dashboardView.slotAssignments = vehicleLayoutManager.getSlotAssignments(layoutKey)
        binding.dashboardView.invalidate()
    }

    private fun promptPendingCrashReportIfNeeded() {
        if (!logShareManager.hasPendingCrash()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Crash Detected")
            .setMessage("AutoVakt crashed last time. Share a diagnostic report?")
            .setPositiveButton("Share") { _, _ ->
                logShareManager.clearPendingCrash()
                logShareManager.shareLogs(this)
            }
            .setNegativeButton("Dismiss") { _, _ ->
                logShareManager.clearPendingCrash()
            }
            .show()
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
                R.id.nav_connect        -> onConnectClicked()
                R.id.nav_disconnect     -> onDisconnectClicked()
                R.id.nav_change_adapter -> onChangeAdapterClicked()
                R.id.nav_stop_trip      -> viewModel.stopTrip()
                R.id.nav_settings       -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_history        -> startActivity(Intent(this, HistoryActivity::class.java))
                R.id.nav_demo           -> toggleDemoMode()
            }
            true
        }
    }

    private fun setupHamburgerReveal() {
        binding.dashboardView.setOnClickListener { showHamburger() }
        binding.btnHamburger.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now > diagWindowEndMs) {
                diagTapCount = 0
                diagWindowEndMs = now + 2_000L
            }
            diagTapCount++
            binding.btnHamburger.removeCallbacks(openDrawerRunnable)

            if (diagTapCount >= 5) {
                // Diagnostics trigger: cancel any pending drawer open and share logs
                diagTapCount = 0
                diagWindowEndMs = 0L
                logShareManager.shareLogs(this)
            } else {
                // Defer drawer open 300 ms so rapid re-taps can accumulate without the
                // drawer scrim blocking subsequent taps.
                binding.btnHamburger.postDelayed(openDrawerRunnable, 300L)
            }
        }
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                // Reset counter when user deliberately closes the drawer
                diagTapCount = 0
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

    private fun setupNavVersion() {
        val header = binding.navigationView.getHeaderView(0)
        header.findViewById<TextView>(R.id.navHeaderVersion).text = "v${BuildConfig.VERSION_NAME}"

        UpdateChecker.check(lifecycleScope, BuildConfig.VERSION_NAME) { newVersion ->
            Toast.makeText(this, "Update available: v$newVersion — get it from GitHub Releases", Toast.LENGTH_LONG).show()
        }
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
        lifecycleScope.launch {
            viewModel.currentLayoutKey.collectLatest { key ->
                binding.dashboardView.gaugeLayout = vehicleLayoutManager.getLayout(key, this@MainActivity, isAA = false)
                binding.dashboardView.slotAssignments = vehicleLayoutManager.getSlotAssignments(key)
                binding.dashboardView.invalidate()
            }
        }
    }

    private fun updateDrawerState(state: ConnectionState) {
        val menu = binding.navigationView.menu
        val isConnected = state is ConnectionState.Connected
        val hasSaved = sharedPreferences.getString("saved_device_address", null) != null
        menu.findItem(R.id.nav_connect).isVisible        = !isConnected
        menu.findItem(R.id.nav_disconnect).isVisible     = isConnected
        menu.findItem(R.id.nav_change_adapter).isVisible = hasSaved
    }

    private fun updatePreConnectionDim(data: com.rogerneumann.autovakt.data.AutoVaktLiveData) {
        val isConnected = data.connectionState is ConnectionState.Connected
        val hasSavedDevice = sharedPreferences.contains("saved_device_address")
        val showDim = !isConnected && !hasSavedDevice
        binding.viewDimScrim.visibility = if (showDim) View.VISIBLE else View.GONE
    }

    private fun updateNavHeaderVehicle(data: com.rogerneumann.autovakt.data.AutoVaktLiveData) {
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

    private fun onChangeAdapterClicked() {
        stopService(Intent(this, OBD2ForegroundService::class.java))
        sharedPreferences.edit()
            .remove("saved_device_address")
            .remove("saved_device_type")
            .apply()
        DeviceScanFragment().show(supportFragmentManager, "device_scan")
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
        val prefs = getSharedPreferences("autovakt_prefs", Context.MODE_PRIVATE)
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

    private fun applyScreenWakeSetting() {
        val mode = sharedPreferences.getString("screen_wake_mode", "off")
        val keepOn = when (mode) {
            "always"   -> true
            "charging" -> isCharging()
            else       -> false
        }
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
