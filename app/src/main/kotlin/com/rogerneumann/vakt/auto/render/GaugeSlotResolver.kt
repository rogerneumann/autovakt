package com.rogerneumann.vakt.auto.render

import com.rogerneumann.vakt.data.VaktLiveData
import com.rogerneumann.vakt.data.VehicleProfile

object GaugeSlotResolver {

    private val EMPTY_SLOT = GaugeSlot("--", "--", "")

    /**
     * Resolves a list of slot assignments into renderable [GaugeSlot] instances.
     *
     * Priority:
     * 1. Named fields in [VaktLiveData] via the hardcoded shortName table
     * 2. [VaktLiveData.customPids] map keyed by shortName (label/unit from [VehicleProfile])
     * 3. Null assignment or unknown shortName → [EMPTY_SLOT]
     */
    fun resolve(
        liveData: VaktLiveData,
        assignments: List<String?>,
        profile: VehicleProfile
    ): List<GaugeSlot> = assignments.map { shortName ->
        if (shortName == null) return@map EMPTY_SLOT
        resolveOne(liveData, shortName, profile)
    }

    private fun resolveOne(
        liveData: VaktLiveData,
        shortName: String,
        profile: VehicleProfile
    ): GaugeSlot {
        // 1. Named fields
        when (shortName) {
            "SOC"             -> return liveData.soc?.let {
                GaugeSlot("SOC", "%.1f".format(it), "%")
            } ?: EMPTY_SLOT

            "PWR"             -> return liveData.powerKw?.let {
                GaugeSlot("Power", "%.1f".format(it), "kW")
            } ?: EMPTY_SLOT

            "SPEED"           -> return liveData.speedMph?.let {
                GaugeSlot("Speed", "%.1f".format(it), "mph")
            } ?: EMPTY_SLOT

            "RPM"             -> return liveData.rpm?.let {
                GaugeSlot("RPM", it.toString(), "")
            } ?: EMPTY_SLOT

            "HV_V"            -> return liveData.hvVoltage?.let {
                GaugeSlot("HV Volt", "%.1f".format(it), "V")
            } ?: EMPTY_SLOT

            "HV_I"            -> return liveData.hvCurrent?.let {
                GaugeSlot("HV Curr", "%.1f".format(it), "A")
            } ?: EMPTY_SLOT

            "BATT_T_MAX"      -> return liveData.battTempMaxC?.let {
                GaugeSlot("Batt Hi", "%.1f".format(it), "°C")
            } ?: EMPTY_SLOT

            "BATT_T_MIN"      -> return liveData.battTempMinC?.let {
                GaugeSlot("Batt Lo", "%.1f".format(it), "°C")
            } ?: EMPTY_SLOT

            "LOAD"            -> return liveData.engineLoad?.let {
                GaugeSlot("Load", "%.1f".format(it), "%")
            } ?: EMPTY_SLOT

            "FUEL_RATE"       -> return liveData.fuelRateGph?.let {
                GaugeSlot("Fuel", "%.1f".format(it), "gph")
            } ?: EMPTY_SLOT

            "BOOST_PSI"       -> return liveData.boostPressurePsi?.let {
                GaugeSlot("Boost", "%.1f".format(it), "psi")
            } ?: EMPTY_SLOT

            "instantMiPerKwh" -> return liveData.instantMiPerKwh?.let {
                GaugeSlot("Inst", "%.1f".format(it), "mi/kWh")
            } ?: EMPTY_SLOT

            "instantMpg"      -> return liveData.instantMpg?.let {
                GaugeSlot("Inst", "%.1f".format(it), "mpg")
            } ?: EMPTY_SLOT

            "averageMiPerKwh" -> return liveData.averageMiPerKwh?.let {
                GaugeSlot("Avg", "%.1f".format(it), "mi/kWh")
            } ?: EMPTY_SLOT

            "averageMpg"      -> return liveData.averageMpg?.let {
                GaugeSlot("Avg", "%.1f".format(it), "mpg")
            } ?: EMPTY_SLOT
        }

        // 2. CustomPid from liveData.customPids; label/unit from profile definition
        val rawValue = liveData.customPids[shortName] ?: return EMPTY_SLOT
        val pidDef = profile.customPids.firstOrNull { it.shortName == shortName }
        val label = pidDef?.name ?: shortName
        val unit  = pidDef?.units ?: ""
        return GaugeSlot(label, "%.1f".format(rawValue), unit)
    }
}
