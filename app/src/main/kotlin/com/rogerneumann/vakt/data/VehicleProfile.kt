package com.rogerneumann.vakt.data

/**
 * Defines the type of vehicle detected. 
 * Influences which Hero Metrics are displayed in the UI.
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
    val vin: String? = null,
    val powertrain: PowertrainType = PowertrainType.UNKNOWN,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val fuelType: String? = null
)
