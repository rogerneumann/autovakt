package com.rogerneumann.vakt.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rogerneumann.vakt.data.GaugeLayout
import com.rogerneumann.vakt.data.LightingManager
import com.rogerneumann.vakt.data.LightingMode
import com.rogerneumann.vakt.data.PidRangeDefaults
import com.rogerneumann.vakt.data.SlotDisplayType
import com.rogerneumann.vakt.data.UnitSystem
import com.rogerneumann.vakt.data.VehicleLayoutManager
import com.rogerneumann.vakt.data.VehicleProfileHub
import com.rogerneumann.vakt.data.VehicleProfileManager
import com.rogerneumann.vakt.BuildConfig
import com.rogerneumann.vakt.databinding.ActivitySettingsBinding
import com.rogerneumann.vakt.obd2.VaktBridgeServer
import com.rogerneumann.vakt.util.LogShareManager
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
    @Inject lateinit var logShareManager: LogShareManager

    private lateinit var binding: ActivitySettingsBinding

    // Prevents live-save listeners from firing during initial UI restore
    private var settingsInitialized = false

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
        setupShareLogsButton()
        setupCloseButton()
        binding.tvVersion.text = "Vakt v${BuildConfig.VERSION_NAME}"
        settingsInitialized = true
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

        // Setup spinners + display-type controls
        val currentAssignments = vehicleLayoutManager.getSlotAssignments(key)

        // Track current shortName for each slot so we can save display type per-shortName
        val currentShortNames = Array<String?>(6) { i ->
            if (i < currentAssignments.size) currentAssignments[i] else null
        }

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
            val displayTypeRow = when (i) {
                0 -> binding.displayTypeRow0
                1 -> binding.displayTypeRow1
                2 -> binding.displayTypeRow2
                3 -> binding.displayTypeRow3
                4 -> binding.displayTypeRow4
                5 -> binding.displayTypeRow5
                else -> continue
            }
            val btnN = when (i) {
                0 -> binding.btnDisplayN0; 1 -> binding.btnDisplayN1
                2 -> binding.btnDisplayN2; 3 -> binding.btnDisplayN3
                4 -> binding.btnDisplayN4; 5 -> binding.btnDisplayN5
                else -> continue
            }
            val btnArc = when (i) {
                0 -> binding.btnDisplayArc0; 1 -> binding.btnDisplayArc1
                2 -> binding.btnDisplayArc2; 3 -> binding.btnDisplayArc3
                4 -> binding.btnDisplayArc4; 5 -> binding.btnDisplayArc5
                else -> continue
            }
            val btnBar = when (i) {
                0 -> binding.btnDisplayBar0; 1 -> binding.btnDisplayBar1
                2 -> binding.btnDisplayBar2; 3 -> binding.btnDisplayBar3
                4 -> binding.btnDisplayBar4; 5 -> binding.btnDisplayBar5
                else -> continue
            }
            val etMin = when (i) {
                0 -> binding.etMin0; 1 -> binding.etMin1
                2 -> binding.etMin2; 3 -> binding.etMin3
                4 -> binding.etMin4; 5 -> binding.etMin5
                else -> continue
            }
            val etMax = when (i) {
                0 -> binding.etMax0; 1 -> binding.etMax1
                2 -> binding.etMax2; 3 -> binding.etMax3
                4 -> binding.etMax4; 5 -> binding.etMax5
                else -> continue
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerOptions)
            spinner.adapter = adapter

            // Restore spinner selection and init display-type row
            val currentValue = currentShortNames[i]
            val position = if (currentValue.isNullOrEmpty()) 0 else spinnerOptions.indexOf(currentValue)
            spinner.setSelection(if (position >= 0) position else 0)

            // Init display-type controls for the current shortName
            if (!currentValue.isNullOrEmpty()) {
                displayTypeRow.visibility = View.VISIBLE
                refreshDisplayTypeControls(currentValue, btnN, btnArc, btnBar, etMin, etMax)
            } else {
                displayTypeRow.visibility = View.GONE
            }

            // Spinner item selected listener
            spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val selected = spinnerOptions[pos]
                    val shortName = if (selected == "— empty —") null else selected
                    currentShortNames[i] = shortName
                    vehicleLayoutManager.saveSlotAssignment(key, i, shortName)
                    vehicleLayoutManager.setConfigured(key, true)

                    if (shortName != null) {
                        displayTypeRow.visibility = View.VISIBLE
                        refreshDisplayTypeControls(shortName, btnN, btnArc, btnBar, etMin, etMax)
                    } else {
                        displayTypeRow.visibility = View.GONE
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })

            // Display-type button listeners
            btnN.setOnClickListener {
                val sn = currentShortNames[i] ?: return@setOnClickListener
                vehicleLayoutManager.saveSlotDisplayType(sn, SlotDisplayType.NUMERIC)
                refreshDisplayTypeControls(sn, btnN, btnArc, btnBar, etMin, etMax)
            }
            btnArc.setOnClickListener {
                val sn = currentShortNames[i] ?: return@setOnClickListener
                vehicleLayoutManager.saveSlotDisplayType(sn, SlotDisplayType.ARC)
                refreshDisplayTypeControls(sn, btnN, btnArc, btnBar, etMin, etMax)
            }
            btnBar.setOnClickListener {
                val sn = currentShortNames[i] ?: return@setOnClickListener
                vehicleLayoutManager.saveSlotDisplayType(sn, SlotDisplayType.BAR)
                refreshDisplayTypeControls(sn, btnN, btnArc, btnBar, etMin, etMax)
            }

            // Min/max text watchers
            etMin.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val sn = currentShortNames[i] ?: return
                    val minVal = s?.toString()?.toFloatOrNull() ?: return
                    val maxVal = etMax.text.toString().toFloatOrNull() ?: return
                    vehicleLayoutManager.saveSlotMinMax(sn, minVal, maxVal)
                }
            })
            etMax.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val sn = currentShortNames[i] ?: return
                    val minVal = etMin.text.toString().toFloatOrNull() ?: return
                    val maxVal = s?.toString()?.toFloatOrNull() ?: return
                    vehicleLayoutManager.saveSlotMinMax(sn, minVal, maxVal)
                }
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

    /**
     * Refreshes the 3-button toggle and min/max fields for a given [shortName].
     * Reads the saved (or default) display type and min/max from [vehicleLayoutManager].
     */
    private fun refreshDisplayTypeControls(
        shortName: String,
        btnN: Button, btnArc: Button, btnBar: Button,
        etMin: EditText, etMax: EditText
    ) {
        val displayType = vehicleLayoutManager.getSlotDisplayType(shortName)
        val (min, max) = vehicleLayoutManager.getSlotMinMax(shortName)

        val activeColor  = Color.parseColor("#00E676")
        val inactiveColor = Color.parseColor("#444444")

        btnN.backgroundTintList   = android.content.res.ColorStateList.valueOf(if (displayType == SlotDisplayType.NUMERIC) activeColor else inactiveColor)
        btnArc.backgroundTintList = android.content.res.ColorStateList.valueOf(if (displayType == SlotDisplayType.ARC)     activeColor else inactiveColor)
        btnBar.backgroundTintList = android.content.res.ColorStateList.valueOf(if (displayType == SlotDisplayType.BAR)     activeColor else inactiveColor)

        val showMinMax = displayType != SlotDisplayType.NUMERIC
        etMin.visibility = if (showMinMax) View.VISIBLE else View.GONE
        etMax.visibility = if (showMinMax) View.VISIBLE else View.GONE

        if (showMinMax) {
            // Only set text if the field is empty or value has changed, to avoid fighting text watchers
            val minStr = formatMinMax(min)
            val maxStr = formatMinMax(max)
            if (etMin.text.toString() != minStr) etMin.setText(minStr)
            if (etMax.text.toString() != maxStr) etMax.setText(maxStr)
        }
    }

    private fun formatMinMax(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else "%.1f".format(value)

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
            if (settingsInitialized) saveThemeSettings()
        }

        binding.radioGroupAutoMode.setOnCheckedChangeListener { _, _ ->
            updateAutoModeSectionVisibility()
            if (settingsInitialized) saveThemeSettings()
        }

        binding.sliderDawnDusk.addOnChangeListener { _, _, _ ->
            binding.tvDawnTime.text = formatHour(binding.sliderDawnDusk.values[0], is24h)
            binding.tvDuskTime.text = formatHour(binding.sliderDawnDusk.values[1], is24h)
        }
        binding.sliderDawnDusk.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                if (settingsInitialized) saveThemeSettings()
            }
        })

        binding.sliderLuxThreshold.addOnChangeListener { _, value, _ ->
            binding.tvLuxValue.text = "Switch to light above: ${value.toInt()} lux"
        }
        binding.sliderLuxThreshold.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                if (settingsInitialized) saveThemeSettings()
            }
        })

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
            if (settingsInitialized) saveThemeSettings()
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

        binding.spinnerVehicle.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!settingsInitialized) return
                if (pos == 0) {
                    profileManager.setActiveProfile("auto")
                } else {
                    profileManager.setActiveProfile(profiles[pos - 1].id)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupUnitButtons() {
        if (profileManager.getUnitPreference() == UnitSystem.METRIC) {
            binding.radioMetric.isChecked = true
        } else {
            binding.radioImperial.isChecked = true
        }

        listOf(binding.radioMetric, binding.radioImperial).forEach { rb ->
            rb.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked || !settingsInitialized) return@setOnCheckedChangeListener
                profileManager.setUnitPreference(
                    if (binding.radioMetric.isChecked) UnitSystem.METRIC else UnitSystem.IMPERIAL
                )
            }
        }
    }

    // ── Import profile ────────────────────────────────────────────────────────

    private fun setupShareLogsButton() {
        binding.btnShareLogs.setOnClickListener {
            logShareManager.shareLogs(this)
        }
    }

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

    // ── Close ─────────────────────────────────────────────────────────────────

    private fun setupCloseButton() {
        binding.btnSave.setOnClickListener { finish() }
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
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "0.0.0.0"
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip == 0) return "0.0.0.0"
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    companion object {
        private const val IMPORT_FILE_REQUEST_CODE = 42
    }
}
