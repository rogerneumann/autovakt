package com.rogerneumann.autovakt.data

import android.content.Context
import android.util.Log
import com.rogerneumann.autovakt.obd2.PidFormulaParser
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleProfileHub @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val profiles = mutableMapOf<String, VehicleProfile>()

    init {
        loadEmbeddedProfiles()
        loadUserImportedProfiles()
    }

    /**
     * Loads the "Starter Pack" from the app assets.
     */
    private fun loadEmbeddedProfiles() {
        try {
            val assetManager = context.assets
            val files = assetManager.list("profiles") ?: return

            for (fileName in files) {
                if (fileName.endsWith(".json")) {
                    val jsonString = assetManager.open("profiles/$fileName").bufferedReader().use { it.readText() }
                    val profile = parseProfile(jsonString)
                    profiles[profile.id] = profile
                }
            }
        } catch (e: Exception) {
            Log.e("VehicleProfileHub", "Failed to load embedded profiles", e)
        }
    }

    /**
     * Loads user-imported profiles from filesDir/profiles/
     */
    private fun loadUserImportedProfiles() {
        try {
            val profilesDir = java.io.File(context.filesDir, "profiles")
            if (!profilesDir.exists()) {
                return
            }

            val files = profilesDir.listFiles { file -> file.name.endsWith(".json") } ?: return
            for (file in files) {
                try {
                    val jsonString = file.readText()
                    val profile = parseProfile(jsonString)
                    profiles[profile.id] = profile
                } catch (e: Exception) {
                    Log.e("VehicleProfileHub", "Skipping malformed profile: ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("VehicleProfileHub", "Failed to load user-imported profiles", e)
        }
    }

    /**
     * Returns all available profiles for selection.
     */
    fun getAvailableProfiles(): List<VehicleProfile> {
        return profiles.values.toList()
    }

    /**
     * Finds a profile by its unique ID.
     */
    fun getProfile(id: String): VehicleProfile? {
        return profiles[id]
    }

    private fun parseProfile(jsonString: String): VehicleProfile {
        val json = JSONObject(jsonString)

        val customPids = buildList {
            val pidsJson = json.optJSONArray("pids") ?: return@buildList
            for (i in 0 until pidsJson.length()) {
                val p = pidsJson.getJSONObject(i)
                val nonLinearMap = buildList mapPoints@{
                    val mapJson = p.optJSONArray("nonLinearMap") ?: return@mapPoints
                    for (j in 0 until mapJson.length()) {
                        val pt = mapJson.getJSONArray(j)
                        add(pt.getDouble(0).toFloat() to pt.getDouble(1).toFloat())
                    }
                }
                add(CustomPid(
                    name = p.getString("name"),
                    shortName = p.getString("shortName"),
                    modeAndPid = p.getString("modeAndPid"),
                    equation = p.getString("equation"),
                    minValue = p.optDouble("minValue", 0.0).toFloat(),
                    maxValue = p.optDouble("maxValue", 100.0).toFloat(),
                    units = p.optString("units", ""),
                    header = p.optString("header").takeIf { it.isNotEmpty() },
                    nonLinearMap = nonLinearMap
                ))
            }
        }

        val initCommands = buildList {
            val arr = json.optJSONArray("initCommands") ?: return@buildList
            for (i in 0 until arr.length()) add(arr.getString(i))
        }

        val vinPatterns = buildList {
            val arr = json.optJSONArray("vinPatterns") ?: return@buildList
            for (i in 0 until arr.length()) add(arr.getString(i))
        }

        val defaultSlots = buildList {
            val arr = json.optJSONArray("defaultSlots") ?: return@buildList
            for (i in 0 until arr.length()) add(arr.getString(i))
        }

        return VehicleProfile(
            id = json.getString("id"),
            make = json.optString("make"),
            model = json.optString("model"),
            year = json.optInt("year"),
            region = json.optString("region"),
            powertrain = PowertrainType.valueOf(json.optString("powertrain", "UNKNOWN")),
            customPids = customPids,
            initCommands = initCommands,
            vinPatterns = vinPatterns,
            defaultSlots = defaultSlots
        )
    }

    fun findProfileByVin(vin: String): List<VehicleProfile> =
        profiles.values.filter { profile ->
            profile.vinPatterns.any { pattern -> vin.startsWith(pattern, ignoreCase = true) }
        }

    /**
     * Imports a profile from JSON string, validates required fields, saves to filesDir/profiles/,
     * and reloads the profiles map.
     *
     * @param jsonString The JSON string containing the profile definition
     * @throws IllegalArgumentException if JSON is invalid or missing required fields
     * @throws Exception if file write fails
     */
    fun importProfileFromJson(jsonString: String) {
        // Parse and validate the profile
        val profile = parseProfile(jsonString)

        // Validate required fields
        require(profile.id.isNotBlank()) { "Profile must have an 'id' field" }
        require(profile.make != null && profile.make.isNotBlank()) { "Profile must have a 'make' field" }
        require(profile.customPids.isNotEmpty()) { "Profile must have at least one PID in 'customPids'" }

        // Save to filesDir/profiles/<id>.json
        val profilesDir = java.io.File(context.filesDir, "profiles")
        if (!profilesDir.exists()) {
            profilesDir.mkdirs()
        }

        val profileFile = java.io.File(profilesDir, "${profile.id}.json")
        profileFile.writeText(jsonString)

        // Update the profiles map and reload everything
        reloadProfiles()
    }

    /**
     * Reloads all profiles from both embedded assets and user-imported files.
     * Call after importing a new profile.
     */
    private fun reloadProfiles() {
        profiles.clear()
        loadEmbeddedProfiles()
        loadUserImportedProfiles()
    }
}
