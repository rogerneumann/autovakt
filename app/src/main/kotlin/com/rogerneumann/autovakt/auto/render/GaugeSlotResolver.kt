package com.rogerneumann.autovakt.auto.render

import com.rogerneumann.autovakt.data.SlotDisplayType
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.data.VehicleLayoutManager
import com.rogerneumann.autovakt.data.VehicleProfile

object GaugeSlotResolver {

    private val EMPTY_SLOT = GaugeSlot("--", "--", "")

    /**
     * Resolves a list of slot assignments into renderable [GaugeSlot] instances.
     *
     * Priority:
     * 1. Named fields in [AutoVaktLiveData] via the hardcoded shortName table
     * 2. [AutoVaktLiveData.customPids] map keyed by shortName (label/unit from [VehicleProfile])
     * 3. Null assignment or unknown shortName → [EMPTY_SLOT]
     */
    fun resolve(
        liveData: AutoVaktLiveData,
        assignments: List<String?>,
        profile: VehicleProfile,
        layoutManager: VehicleLayoutManager
    ): List<GaugeSlot> = assignments.map { shortName ->
        if (shortName == null) return@map EMPTY_SLOT
        resolveOne(liveData, shortName, profile, layoutManager)
    }

    private fun resolveOne(
        liveData: AutoVaktLiveData,
        shortName: String,
        profile: VehicleProfile,
        layoutManager: VehicleLayoutManager
    ): GaugeSlot {
        // 1. Named fields
        val base: GaugeSlot? = when (shortName) {
            "SOC"             -> liveData.soc?.let {
                GaugeSlot("SOC", "%.1f".format(it), "%")
            }

            "PWR"             -> liveData.powerKw?.let {
                GaugeSlot("Power", "%.1f".format(it), "kW")
            }

            "SPEED"           -> liveData.speedMph?.let {
                GaugeSlot("Speed", "%.1f".format(it), "mph")
            }

            "RPM"             -> liveData.rpm?.let {
                GaugeSlot("RPM", it.toString(), "")
            }

            "HV_V"            -> liveData.hvVoltage?.let {
                GaugeSlot("HV Volt", "%.1f".format(it), "V")
            }

            "HV_I"            -> liveData.hvCurrent?.let {
                GaugeSlot("HV Curr", "%.1f".format(it), "A")
            }

            "BATT_T_MAX"      -> liveData.battTempMaxC?.let {
                GaugeSlot("Batt Hi", "%.1f".format(it), "°C")
            }

            "BATT_T_MIN"      -> liveData.battTempMinC?.let {
                GaugeSlot("Batt Lo", "%.1f".format(it), "°C")
            }

            "LOAD"            -> liveData.engineLoad?.let {
                GaugeSlot("Load", "%.1f".format(it), "%")
            }

            "FUEL_RATE"       -> liveData.fuelRateGph?.let {
                GaugeSlot("Fuel", "%.1f".format(it), "gph")
            }

            "BOOST_PSI"       -> liveData.boostPressurePsi?.let {
                GaugeSlot("Boost", "%.1f".format(it), "psi")
            }

            "instantMiPerKwh" -> liveData.instantMiPerKwh?.let {
                GaugeSlot("Inst", "%.1f".format(it), "mi/kWh")
            }

            "instantMpg"      -> liveData.instantMpg?.let {
                GaugeSlot("Inst", "%.1f".format(it), "mpg")
            }

            "averageMiPerKwh" -> liveData.averageMiPerKwh?.let {
                GaugeSlot("Avg", "%.1f".format(it), "mi/kWh")
            }

            "averageMpg"      -> liveData.averageMpg?.let {
                GaugeSlot("Avg", "%.1f".format(it), "mpg")
            }

            else -> null  // fall through to custom PID lookup
        }

        // 2. CustomPid from liveData.customPids; label/unit from profile definition
        val resolved: GaugeSlot = base ?: run {
            val rawValue = liveData.customPids[shortName] ?: return EMPTY_SLOT
            val pidDef = profile.customPids.firstOrNull { it.shortName == shortName }
            val label = pidDef?.name ?: shortName
            val unit  = pidDef?.units ?: ""
            GaugeSlot(label, "%.1f".format(rawValue), unit)
        }

        if (resolved.value == "--") return EMPTY_SLOT

        // 3. Enrich with display type and fraction from VehicleLayoutManager
        val displayType = layoutManager.getSlotDisplayType(shortName)
        val (min, max) = layoutManager.getSlotMinMax(shortName)
        val numericVal = resolved.value.toFloatOrNull()
        val fraction = if (displayType != SlotDisplayType.NUMERIC && numericVal != null && max != min) {
            ((numericVal - min) / (max - min)).coerceIn(0f, 1f)
        } else null
        val isBidirectional = displayType == SlotDisplayType.BAR && min < 0f && max > 0f

        return resolved.copy(displayType = displayType, fraction = fraction, isBidirectional = isBidirectional)
    }
}
