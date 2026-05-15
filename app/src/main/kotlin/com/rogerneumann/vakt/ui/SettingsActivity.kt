package com.rogerneumann.vakt.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.vakt.data.GaugeLayout
import com.rogerneumann.vakt.data.LightingManager
import com.rogerneumann.vakt.data.LightingMode
import com.rogerneumann.vakt.data.UnitSystem
import com.rogerneumann.vakt.data.VehicleLayoutManager
import com.rogerneumann.vakt.data.VehicleProfileHub
import com.rogerneumann.vakt.data.VehicleProfileManager
import com.rogerneumann.vakt.databinding.ActivitySettingsBinding
import com.rogerneumann.vakt.obd2.VaktBridgeServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject lateinit var profileHub: VehicleProfileHub
    @Inject lateinit var profileManager: VehicleProfileManager
    @Inject lateinit var lightingManager: LightingManager
    @Inject lateinit var vehicleLayoutManager: VehicleLayoutManager
    @Inject lateinit var bridgeServer: VaktBridgeServer
    @Inject lateinit var sharedPreferences: SharedPreferences

    private lateinit var binding: ActivitySettingsBinding

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            fetchAndCacheLocation()
        } else {
            binding.switchUseLocation.isChecked = false
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVehicleSpinner()
        setupUnitButtons()
        setupThemeSection()
        setupDashboardLayoutSection()
        setupBridgeSection()
        setupImportButton()
        setupSaveButton()
    }

    // ── Dashboard Layout section ──────────────────────────────────────────────

    private fun setupDashboardLayoutSection() {
        val profile = profileManager.getActiveProfile()
        val key = vehicleLayoutManager.resolveKey(null, null, profile.id)

        // Setup layout radio group
        val currentLayout = vehicleLayoutManager.getLayout(key, this, false)
        when (currentLayout) {
            GaugeLayout.GRID_2   -> binding.radioLayout2.isChecked = true
            GaugeLayout.GRID_4   -> binding.radioLayout4.isChecked = true
            GaugeLayout.GRID_2x3 -> binding.radioLayout2x3.isChecked = true
            GaugeLayout.ARC      -> binding.radioLayoutArc.isChecked = true
        }

        // Build spinner options: empty + profile shortNames + standard shortNames
        val standardShortNames = listOf(
            "SOC", "PWR", "SPEED", "RPM", "instantMiPerKwh", "instantMpg",
            "averageMiPerKwh", "averageMpg", "HV_V", "HV_I",
            "BATT_T_MAX", "BATT_T_MIN", "LOAD", "FUEL_RATE", "BOOST_PSI"
        )
        val profileShortNames = profile.customPids.map { it.shortName }.toSet()
        val allShortNames = (profileShortNames + standardShortNames).distinct().sorted()
        val spinnerOptions = listOf("— empty —") + allShortNames

        // Setup spinners
        val currentAssignments = vehicleLayoutManager.getSlotAssignments(key)
        val spinnerAdapters = mutableListOf<ArrayAdapter<String>>()

        for (i in 0..5) {
            val spinner = when (i) {
                0 -> binding.spinnerSlot0
                1 -> binding.spinnerSlot1
                2 -> binding.spinnerSlot2
                3 -> binding.spinnerSlot3
                4 -> binding.spinnerSlot4
                5 -> binding.spinnerSlot5
                else -> continue
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerOptions)
            spinner.adapter = adapter
            spinnerAdapters.add(adapter)

            // Restore selection
            val currentValue = if (i < currentAssignments.size) currentAssignments[i] else null
            val position = if (currentValue.isNullOrEmpty()) 0 else spinnerOptions.indexOf(currentValue)
            spinner.setSelection(if (position >= 0) position else 0)

            // On change listener
            spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = spinnerOptions[position]
                    val shortName = if (selected == "— empty —") null else selected
                    vehicleLayoutManager.saveSlotAssignment(key, i, shortName)
                    vehicleLayoutManager.setConfigured(key, true)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
        }

        // Layout radio group listener
        binding.radioGroupLayout.setOnCheckedChangeListener { _, _ ->
            val layout = when {
                binding.radioLayout2.isChecked -> GaugeLayout.GRID_2
                binding.radioLayout4.isChecked -> GaugeLayout.GRID_4
                binding.radioLayout2x3.isChecked -> GaugeLayout.GRID_2x3
                binding.radioLayoutArc.isChecked -> GaugeLayout.ARC
                else -> GaugeLayout.GRID_4
            }

            vehicleLayoutManager.saveLayout(key, layout)
            vehicleLayoutManager.setConfigured(key, true)
            updateSlotVisibility(layout)
        }

        // Initial slot visibility
        updateSlotVisibility(currentLayout)

        // Reset to defaults button
        binding.btnResetLayout.setOnClickListener {
            vehicleLayoutManager.resetToDefaults(key, profile)
            // Refresh spinners
            val newAssignments = vehicleLayoutManager.getSlotAssignments(key)
            for (i in 0..5) {
                val currentValue = if (i < newAssignments.size) newAssignments[i] else null
                val position = if (currentValue.isNullOrEmpty()) 0 else spinnerOptions.indexOf(currentValue)
                when (i) {
                    0 -> binding.spinnerSlot0.setSelection(if (position >= 0) position else 0)
                    1 -> binding.spinnerSlot1.setSelection(if (position >= 0) position else 0)
                    2 -> binding.spinnerSlot2.setSelection(if (position >= 0) position else 0)
                    3 -> binding.spinnerSlot3.setSelection(if (position >= 0) position else 0)
                    4 -> binding.spinnerSlot4.setSelection(if (position >= 0) position else 0)
                    5 -> binding.spinnerSlot5.setSelection(if (position >= 0) position else 0)
                }
            }
        }

        binding.btnSavedVehicles.setOnClickListener {
            startActivity(Intent(this, com.rogerneumann.vakt.ui.vehicles.SavedVehiclesActivity::class.java))
        }
    }

    private fun updateSlotVisibility(layout: GaugeLayout) {
        val show2x3 = layout == GaugeLayout.GRID_2x3
        binding.tvLayout2x3Warning.visibility = if (show2x3) View.VISIBLE else View.GONE
        binding.layoutSlot4.visibility = if (show2x3) View.VISIBLE else View.GONE
        binding.layoutSlot5.visibility = if (show2x3) View.VISIBLE else View.GONE
    }

    // ── Theme section ─────────────────────────────────────────────────────────

    private fun setupThemeSection() {
        val is24h = DateFormat.is24HourFormat(this)

        restoreThemeRadioGroup()
        restoreAutoModeRadioGroup()
        restoreTimeSlider(is24h)
        restoreLuxSlider()
        binding.switchUseLocation.isChecked = lightingManager.isUseLocation()

        updateThemeSectionVisibility()

        binding.radioGroupTheme.setOnCheckedChangeListener { _, _ ->
            updateThemeSectionVisibility()
            if (!binding.radioThemeAuto.isChecked) {
                saveThemeSettings()
            }
        }

        binding.radioGroupAutoMode.setOnCheckedChangeListener { _, _ ->
            updateAutoModeSectionVisibility()
        }

        binding.sliderDawnDusk.addOnChangeListener { _, _, _ ->
            binding.tvDawnTime.text = formatHour(binding.sliderDawnDusk.values[0], is24h)
            binding.tvDuskTime.text = formatHour(binding.sliderDawnDusk.values[1], is24h)
        }

        binding.sliderLuxThreshold.addOnChangeListener { _, value, _ ->
            binding.tvLuxValue.text = "Switch to light above: ${value.toInt()} lux"
        }

        binding.switchUseLocation.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    fetchAndCacheLocation()
                } else {
                    locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (!lightingManager.hasCachedLocation()) {
                    Toast.makeText(this, "No GPS fix yet — open the app outdoors to cache a location", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restoreThemeRadioGroup() {
        when (lightingManager.getMode()) {
            LightingMode.DARK                          -> binding.radioThemeDark.isChecked = true
            LightingMode.LIGHT                         -> binding.radioThemeLight.isChecked = true
            LightingMode.AUTO_TIME, LightingMode.AUTO_SENSOR, LightingMode.AUTO_BEST ->
                binding.radioThemeAuto.isChecked = true
        }
    }

    private fun restoreAutoModeRadioGroup() {
        when (lightingManager.getMode()) {
            LightingMode.AUTO_SENSOR -> binding.radioAutoSensor.isChecked = true
            LightingMode.AUTO_BEST   -> binding.radioAutoBest.isChecked = true
            else                     -> binding.radioAutoTime.isChecked = true
        }
    }

    private fun restoreTimeSlider(is24h: Boolean) {
        val dawn = lightingManager.getDawnHour()
        val dusk = lightingManager.getDuskHour()
        binding.sliderDawnDusk.values = mutableListOf(dawn, dusk)
        binding.tvDawnTime.text = formatHour(dawn, is24h)
        binding.tvDuskTime.text = formatHour(dusk, is24h)
    }

    private fun restoreLuxSlider() {
        val lux = lightingManager.getLuxThreshold()
        binding.sliderLuxThreshold.value = lux
        binding.tvLuxValue.text = "Switch to light above: ${lux.toInt()} lux"
    }

    private fun updateThemeSectionVisibility() {
        val isAuto = binding.radioThemeAuto.isChecked
        binding.sectionAutoTheme.visibility = if (isAuto) View.VISIBLE else View.GONE
        if (isAuto) updateAutoModeSectionVisibility()
        else {
            binding.sectionTimeTheme.visibility   = View.GONE
            binding.sectionSensorTheme.visibility = View.GONE
        }
    }

    private fun updateAutoModeSectionVisibility() {
        val showTime   = binding.radioAutoTime.isChecked || binding.radioAutoBest.isChecked
        val showSensor = binding.radioAutoSensor.isChecked || binding.radioAutoBest.isChecked
        binding.sectionTimeTheme.visibility   = if (showTime)   View.VISIBLE else View.GONE
        binding.sectionSensorTheme.visibility = if (showSensor) View.VISIBLE else View.GONE
    }

    private fun selectedThemeMode(): LightingMode = when {
        binding.radioThemeDark.isChecked  -> LightingMode.DARK
        binding.radioThemeLight.isChecked -> LightingMode.LIGHT
        binding.radioAutoSensor.isChecked -> LightingMode.AUTO_SENSOR
        binding.radioAutoBest.isChecked   -> LightingMode.AUTO_BEST
        else                              -> LightingMode.AUTO_TIME
    }

    private fun saveThemeSettings() {
        lightingManager.saveSettings(
            mode         = selectedThemeMode(),
            dawnHour     = binding.sliderDawnDusk.values[0],
            duskHour     = binding.sliderDawnDusk.values[1],
            useLocation  = binding.switchUseLocation.isChecked,
            luxThreshold = binding.sliderLuxThreshold.value
        )
    }

    private fun formatHour(hour: Float, is24h: Boolean): String {
        val h = hour.toInt()
        val m = if (hour % 1f > 0f) "30" else "00"
        return if (is24h) {
            "%d:%s".format(h, m)
        } else {
            val h12  = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
            val amPm = if (h < 12) "AM" else "PM"
            "%d:%s %s".format(h12, m, amPm)
        }
    }

    @Suppress("MissingPermission")
    private fun fetchAndCacheLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val loc = lm.getLastKnownLocation(provider)
            if (loc != null) {
                lightingManager.updateLocation(loc.latitude, loc.longitude)
                Toast.makeText(
                    this,
                    "Location cached (%.4f, %.4f)".format(loc.latitude, loc.longitude),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        Toast.makeText(this, "No cached location — will update when GPS fixes", Toast.LENGTH_SHORT).show()
    }

    // ── Vehicle & unit ────────────────────────────────────────────────────────

    private fun setupVehicleSpinner() {
        val profiles = profileHub.getAvailableProfiles()
        val labels = listOf("Auto (VIN detection)") + profiles.map { "${it.make} ${it.model} (${it.year})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerVehicle.adapter = adapter

        val activeId = profileManager.getActiveProfileId()
        if (activeId == "auto") {
            binding.spinnerVehicle.setSelection(0)
        } else {
            val index = profiles.indexOfFirst { it.id == activeId }
            binding.spinnerVehicle.setSelection(if (index >= 0) index + 1 else 0)
        }
    }

    private fun setupUnitButtons() {
        if (profileManager.getUnitPreference() == UnitSystem.METRIC) {
            binding.radioMetric.isChecked = true
        } else {
            binding.radioImperial.isChecked = true
        }
    }

    // ── Import profile ────────────────────────────────────────────────────────

    private fun setupImportButton() {
        binding.btnImportProfile.setOnClickListener {
            showImportDialog()
        }
    }

    private fun showImportDialog() {
        val options = arrayOf("From URL", "From File")
        AlertDialog.Builder(this)
            .setTitle("Import Profile")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUrlImportDialog()
                    1 -> startFilePicker()
                }
            }
            .show()
    }

    private fun showUrlImportDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/profile.json"
        }

        AlertDialog.Builder(this)
            .setTitle("Import Profile from URL")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    importProfileFromUrl(url)
                } else {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importProfileFromUrl(url: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val jsonString = downloadJson(url)
                profileHub.importProfileFromJson(jsonString)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Profile imported successfully", Toast.LENGTH_SHORT).show()
                    refreshVehicleSpinner()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Failed to import profile: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, IMPORT_FILE_REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri -> importProfileFromFile(uri) }
        }
    }

    private fun importProfileFromFile(uri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Could not read file")

                profileHub.importProfileFromJson(jsonString)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Profile imported successfully", Toast.LENGTH_SHORT).show()
                    refreshVehicleSpinner()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Failed to import profile: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadJson(url: String): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection.responseCode != 200) {
            throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        }

        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun refreshVehicleSpinner() {
        setupVehicleSpinner()
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val selectedIndex = binding.spinnerVehicle.selectedItemPosition
            if (selectedIndex == 0) {
                profileManager.setActiveProfile("auto")
            } else {
                val selectedProfile = profileHub.getAvailableProfiles()[selectedIndex - 1]
                profileManager.setActiveProfile(selectedProfile.id)
            }

            val unitSystem = if (binding.radioMetric.isChecked) UnitSystem.METRIC else UnitSystem.IMPERIAL
            profileManager.setUnitPreference(unitSystem)

            saveThemeSettings()
            finish()
        }
    }

    // ── Vakt Bridge section ───────────────────────────────────────────────────

    private fun setupBridgeSection() {
        val port = sharedPreferences.getInt("bridge_port", 35000)
        val wifiIp = getWifiIp()
        val address = "$wifiIp:$port"

        // Restore toggle state
        binding.switchBridgeEnabled.isChecked = sharedPreferences.getBoolean("bridge_enabled", true)
        binding.switchBridgeEnabled.setOnCheckedChangeListener { _, checked ->
            sharedPreferences.edit().putBoolean("bridge_enabled", checked).apply()
            bridgeServer.isBridgeEnabled = checked
        }

        // Address display
        binding.tvBridgeAddress.text = address

        // Copy button
        binding.btnBridgeCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Vakt Bridge address", address))
            Toast.makeText(this, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Instructions accordion
        binding.tvBridgeInstructionsTrigger.setOnClickListener {
            val isVisible = binding.layoutBridgeInstructions.visibility == View.VISIBLE
            binding.layoutBridgeInstructions.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvBridgeInstructionsTrigger.text =
                if (isVisible) "Setup instructions ▼" else "Setup instructions ▲"
        }

        // Advanced port field
        binding.tvBridgeAdvancedTrigger.setOnClickListener {
            val isVisible = binding.layoutBridgeAdvanced.visibility == View.VISIBLE
            binding.layoutBridgeAdvanced.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvBridgeAdvancedTrigger.text =
                if (isVisible) "Advanced ▼" else "Advanced ▲"
        }

        binding.etBridgePort.setText(port.toString())
        binding.etBridgePort.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val newPort = s?.toString()?.toIntOrNull() ?: return
                if (newPort in 1024..65535) {
                    sharedPreferences.edit().putInt("bridge_port", newPort).apply()
                    binding.tvBridgeAddress.text = "$wifiIp:$newPort"
                }
            }
        })

        // Observe active client count
        lifecycleScope.launch {
            bridgeServer.activeClientCount.collect { count ->
                binding.tvBridgeStatus.text = if (count > 0)
                    "Active — $count client(s) connected"
                else
                    "Waiting for connection"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    companion object {
        private const val IMPORT_FILE_REQUEST_CODE = 42
    }
}
