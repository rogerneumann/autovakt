package com.rogerneumann.vakt.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleProfileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileHub: VehicleProfileHub
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vakt_prefs", Context.MODE_PRIVATE)

    fun getActiveProfileId(): String =
        prefs.getString("active_vehicle_id", "chevy_bolt_euv_2023") ?: "chevy_bolt_euv_2023"

    fun getActiveProfile(): VehicleProfile {
        val id = getActiveProfileId()
        if (id == "auto") return VehicleProfile.DEFAULT
        return profileHub.getProfile(id) ?: VehicleProfile.DEFAULT
    }

    fun setActiveProfile(id: String) {
        prefs.edit().putString("active_vehicle_id", id).apply()
    }
    
    fun getUnitPreference(): UnitSystem {
        val unit = prefs.getString("unit_system", "IMPERIAL") ?: "IMPERIAL"
        return UnitSystem.valueOf(unit)
    }

    fun setUnitPreference(system: UnitSystem) {
        prefs.edit().putString("unit_system", system.name).apply()
    }
}

enum class UnitSystem {
    METRIC, IMPERIAL
}
