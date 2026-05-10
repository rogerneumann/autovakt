package com.rogerneumann.vakt.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rogerneumann.vakt.data.UnitSystem
import com.rogerneumann.vakt.data.VehicleProfileHub
import com.rogerneumann.vakt.data.VehicleProfileManager
import com.rogerneumann.vakt.databinding.ActivitySettingsBinding
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

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVehicleSpinner()
        setupUnitButtons()
        setupImportButton()
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
                    Toast.makeText(
                        this@SettingsActivity,
                        "Profile imported successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshVehicleSpinner()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Failed to import profile: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, IMPORT_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                importProfileFromFile(uri)
            }
        }
    }

    private fun importProfileFromFile(uri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Could not read file")

                profileHub.importProfileFromJson(jsonString)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Profile imported successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshVehicleSpinner()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Failed to import profile: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
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

    companion object {
        private const val IMPORT_FILE_REQUEST_CODE = 42
    }
}
