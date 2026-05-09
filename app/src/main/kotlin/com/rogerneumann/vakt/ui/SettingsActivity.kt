package com.rogerneumann.vakt.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.rogerneumann.vakt.data.UnitSystem
import com.rogerneumann.vakt.data.VehicleProfileHub
import com.rogerneumann.vakt.data.VehicleProfileManager
import com.rogerneumann.vakt.databinding.ActivitySettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject lateinit var profileHub: VehicleProfileHub
    @Inject lateinit var profileManager: VehicleProfileManager

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVehicleSpinner()
        setupUnitButtons()
        setupSaveButton()
    }

    private fun setupVehicleSpinner() {
        val profiles = profileHub.getAvailableProfiles()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.map { "${it.make} ${it.model} (${it.year})" })
        binding.spinnerVehicle.adapter = adapter
        
        val activeProfile = profileManager.getActiveProfile()
        val index = profiles.indexOfFirst { it.id == activeProfile.id }
        if (index >= 0) binding.spinnerVehicle.setSelection(index)
    }

    private fun setupUnitButtons() {
        if (profileManager.getUnitPreference() == UnitSystem.METRIC) {
            binding.radioMetric.isChecked = true
        } else {
            binding.radioImperial.isChecked = true
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val selectedIndex = binding.spinnerVehicle.selectedItemPosition
            val selectedProfile = profileHub.getAvailableProfiles()[selectedIndex]
            profileManager.setActiveProfile(selectedProfile.id)

            val unitSystem = if (binding.radioMetric.isChecked) UnitSystem.METRIC else UnitSystem.IMPERIAL
            profileManager.setUnitPreference(unitSystem)

            finish()
        }
    }
}
