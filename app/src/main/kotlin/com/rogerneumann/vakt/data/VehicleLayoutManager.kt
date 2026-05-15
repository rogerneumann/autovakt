package com.rogerneumann.vakt.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleLayoutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences
) {

    companion object {
        // Prefix builders — use these to form every key
        private const val PREFIX_VIN     = "gauge_layout_VIN_"
        private const val PREFIX_MAC     = "gauge_layout_MAC_"
        private const val PREFIX_PROFILE = "gauge_layout_profile_"
        private const val PREFIX_GLOBAL  = "gauge_layout_global"

        // Suffix constants — appended to every prefix
        private const val SUFFIX_SLOT          = "_slot_"       // + slotIndex
        private const val SUFFIX_CONFIGURED    = "_configured"
        private const val SUFFIX_LAYOUT        = "_layout"
        private const val SUFFIX_LABEL         = "_label"
        private const val SUFFIX_PROFILE_ID    = "_profileId"
        private const val SUFFIX_LAST_CONNECTED = "_lastConnected"

        // Powertrain-type default slot assignments
        private val DEFAULTS_EV_PHEV    = listOf("SOC", "PWR", "SPEED", "instantMiPerKwh")
        private val DEFAULTS_ICE_GAS    = listOf("RPM", "SPEED", "LOAD", "instantMpg")
        private val DEFAULTS_ICE_DIESEL = listOf("RPM", "SPEED", "LOAD", "instantMpg")
        private val DEFAULTS_UNKNOWN    = listOf("SPEED", "RPM", null, null)

        private const val SLOT_COUNT = 4
    }

    // -------------------------------------------------------------------------
    // Key resolution
    // -------------------------------------------------------------------------

    fun resolveKey(vin: String?, adapterMac: String?, profileId: String): String = when {
        vin != null                -> "$PREFIX_VIN$vin"
        adapterMac != null         -> "$PREFIX_MAC$adapterMac"
        profileId != "auto"        -> "$PREFIX_PROFILE$profileId"
        else                       -> PREFIX_GLOBAL
    }

    // -------------------------------------------------------------------------
    // Slot assignments
    // -------------------------------------------------------------------------

    fun getSlotAssignments(key: String): List<String?> {
        return (0 until SLOT_COUNT).map { n ->
            val value = prefs.getString("$key$SUFFIX_SLOT$n", "")
            if (value.isNullOrEmpty()) null else value
        }
    }

    fun saveSlotAssignment(key: String, slotIndex: Int, shortName: String?) {
        prefs.edit()
            .putString("$key$SUFFIX_SLOT$slotIndex", shortName ?: "")
            .apply()
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    fun getLayout(key: String, context: Context, isAA: Boolean): GaugeLayout {
        if (!isConfigured(key)) return getAutoLayout(context, isAA)
        val stored = prefs.getString("$key$SUFFIX_LAYOUT", null) ?: return getAutoLayout(context, isAA)
        return runCatching { GaugeLayout.valueOf(stored) }.getOrElse { getAutoLayout(context, isAA) }
    }

    fun saveLayout(key: String, layout: GaugeLayout) {
        prefs.edit()
            .putString("$key$SUFFIX_LAYOUT", layout.name)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Configured flag
    // -------------------------------------------------------------------------

    fun isConfigured(key: String): Boolean =
        prefs.getBoolean("$key$SUFFIX_CONFIGURED", false)

    fun setConfigured(key: String, configured: Boolean) {
        prefs.edit()
            .putBoolean("$key$SUFFIX_CONFIGURED", configured)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Reset to defaults
    // -------------------------------------------------------------------------

    fun resetToDefaults(key: String, profile: VehicleProfile) {
        val slots: List<String?> = if (profile.defaultSlots.isNotEmpty()) {
            // Pad or trim to exactly SLOT_COUNT entries
            val padded: MutableList<String?> = profile.defaultSlots.toMutableList()
            while (padded.size < SLOT_COUNT) padded.add(null)
            padded.take(SLOT_COUNT)
        } else {
            when (profile.powertrain) {
                PowertrainType.EV         -> DEFAULTS_EV_PHEV
                PowertrainType.PHEV       -> DEFAULTS_EV_PHEV
                PowertrainType.ICE_GAS    -> DEFAULTS_ICE_GAS
                PowertrainType.ICE_DIESEL -> DEFAULTS_ICE_DIESEL
                PowertrainType.UNKNOWN    -> DEFAULTS_UNKNOWN
            }
        }

        val editor = prefs.edit()
        slots.forEachIndexed { n, shortName ->
            editor.putString("$key$SUFFIX_SLOT$n", shortName ?: "")
        }
        editor.putBoolean("$key$SUFFIX_CONFIGURED", false)
        editor.apply()
    }

    // -------------------------------------------------------------------------
    // User label
    // -------------------------------------------------------------------------

    fun getUserLabel(key: String): String? {
        val value = prefs.getString("$key$SUFFIX_LABEL", "")
        return if (value.isNullOrEmpty()) null else value
    }

    fun setUserLabel(key: String, label: String?) {
        prefs.edit()
            .putString("$key$SUFFIX_LABEL", label ?: "")
            .apply()
    }

    // -------------------------------------------------------------------------
    // Connection tracking
    // -------------------------------------------------------------------------

    fun recordConnection(
        key: String,
        profileId: String,
        vin: String?,
        adapterMac: String?,
        profile: VehicleProfile
    ) {
        val editor = prefs.edit()
        editor.putString("$key$SUFFIX_PROFILE_ID", profileId)
        editor.putLong("$key$SUFFIX_LAST_CONNECTED", System.currentTimeMillis())
        editor.apply()

        if (!isConfigured(key)) {
            resetToDefaults(key, profile)
        }
    }

    // -------------------------------------------------------------------------
    // Saved vehicles
    // -------------------------------------------------------------------------

    fun getSavedVehicles(): List<SavedVehicle> {
        val suffix = SUFFIX_LAST_CONNECTED
        return prefs.all
            .keys
            .filter { it.endsWith(suffix) }
            .mapNotNull { fullKey ->
                val key = fullKey.removeSuffix(suffix)
                buildSavedVehicle(key)
            }
            .sortedByDescending { it.lastConnected }
    }

    private fun buildSavedVehicle(key: String): SavedVehicle? {
        val lastConnected = prefs.getLong("$key$SUFFIX_LAST_CONNECTED", 0L)
        if (lastConnected == 0L) return null

        val profileId = prefs.getString("$key$SUFFIX_PROFILE_ID", "") ?: ""
        val userLabel = getUserLabel(key)

        // Reconstruct VIN / MAC from the key prefix
        val vin: String? = when {
            key.startsWith(PREFIX_VIN) -> key.removePrefix(PREFIX_VIN)
            else -> null
        }
        val adapterMac: String? = when {
            key.startsWith(PREFIX_MAC) -> key.removePrefix(PREFIX_MAC)
            else -> null
        }

        val autoLabel = when {
            vin != null -> {
                // We don't have live profile access here, so build from stored prefs profileId
                // Label: "Unknown ···{last4vin}" as base; blocks 12b+ can enrich from profile
                "Vehicle ···${vin.takeLast(4)}"
            }
            adapterMac != null -> "Adapter ···${adapterMac.takeLast(4)}"
            else -> "Unknown Vehicle"
        }

        return SavedVehicle(
            key = key,
            vin = vin,
            adapterMac = adapterMac,
            profileId = profileId,
            autoLabel = autoLabel,
            userLabel = userLabel,
            lastConnected = lastConnected
        )
    }

    fun deleteVehicle(key: String) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(key) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // -------------------------------------------------------------------------
    // Per-slot display type
    // -------------------------------------------------------------------------

    fun getSlotDisplayType(shortName: String): SlotDisplayType {
        val stored = prefs.getString("slot_display_type_$shortName", null)
        return if (stored != null) {
            runCatching { SlotDisplayType.valueOf(stored) }
                .getOrElse { PidRangeDefaults.defaultDisplayType(shortName) }
        } else {
            PidRangeDefaults.defaultDisplayType(shortName)
        }
    }

    fun saveSlotDisplayType(shortName: String, type: SlotDisplayType) {
        prefs.edit()
            .putString("slot_display_type_$shortName", type.name)
            .apply()
    }

    fun getSlotMinMax(shortName: String): Pair<Float, Float> {
        val defaults = PidRangeDefaults.defaultRange(shortName)
        val min = prefs.getFloat("slot_min_$shortName", defaults.first)
        val max = prefs.getFloat("slot_max_$shortName", defaults.second)
        return min to max
    }

    fun saveSlotMinMax(shortName: String, min: Float, max: Float) {
        prefs.edit()
            .putFloat("slot_min_$shortName", min)
            .putFloat("slot_max_$shortName", max)
            .apply()
    }

    // -------------------------------------------------------------------------
    // Auto-layout detection
    // -------------------------------------------------------------------------

    fun getAutoLayout(context: Context, isAA: Boolean): GaugeLayout {
        val config = context.resources.configuration
        return if (isAA) {
            val widthDp = config.screenWidthDp
            when {
                widthDp < 600  -> GaugeLayout.GRID_2
                widthDp <= 900 -> GaugeLayout.GRID_4
                else           -> GaugeLayout.GRID_2x3
            }
        } else {
            val swDp = config.smallestScreenWidthDp
            when {
                swDp < 400  -> GaugeLayout.GRID_2
                swDp < 600  -> GaugeLayout.GRID_4
                else        -> GaugeLayout.GRID_2x3
            }
        }
    }
}
