package com.rogerneumann.autovakt.data

/**
 * Defines the type of vehicle detected. 
 */
enum class PowertrainType {
    EV,         // Hero: SOC %, Power (kW)
    PHEV,       // Hero: SOC %, Fuel Level
    ICE_GAS,    // Hero: Engine Load, RPM
    ICE_DIESEL, // Hero: Boost, Fuel Rate (GPH)
    UNKNOWN
}

/**
 * Stores the specific capabilities and identifiers for the connected vehicle.
 */
data class VehicleProfile(
    val id: String = "default",
    val vin: String? = null,
    val powertrain: PowertrainType = PowertrainType.UNKNOWN,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val region: String? = null,
    val customPids: List<CustomPid> = emptyList(),
    val initCommands: List<String> = emptyList(),   // AT commands sent after ATZ+ATE0
    val vinPatterns: List<String> = emptyList(),    // VIN prefix strings for auto-matching
    val defaultSlots: List<String> = emptyList()    // Preferred slot shortNames for this profile
) {
    companion object {
        val DEFAULT = VehicleProfile(id = "default", powertrain = PowertrainType.UNKNOWN)
    }
}
