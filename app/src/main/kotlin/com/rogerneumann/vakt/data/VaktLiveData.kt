package com.rogerneumann.vakt.data

import com.rogerneumann.vakt.obd2.ConnectionState
import com.rogerneumann.vakt.obd2.DeviceCapability

/**
 * Core telemetry data class representing the state of the Chevrolet Bolt EUV.
 */
data class VaktLiveData(
    val soc: Float? = null,              // Displayed %, 0-100
    val hvVoltage: Float? = null,         // Volts
    val hvCurrent: Float? = null,         // Amps (positive=discharge, negative=regen)
    val powerKw: Float? = null,           // Calculated: V*I/1000
    val battTempMaxC: Float? = null,
    val battTempMinC: Float? = null,
    val speedMph: Float? = null,
    val instantMiPerKwh: Float? = null,
    val averageMiPerKwh: Float? = null,
    val tripDistanceMiles: Float? = null,
    val tripEnergyKwh: Float? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val deviceCapability: DeviceCapability? = null,
    val vehicleProfile: VehicleProfile = VehicleProfile.DEFAULT,
    val vin: String? = null,
    
    // ICE-specific metrics
    val engineLoad: Float? = null,        // %
    val boostPressurePsi: Float? = null,  // PSI
    val fuelRateGph: Float? = null,       // Gallons per hour
    val coolantTempC: Float? = null,
    
    // Media Info (Proxy from system)
    val currentSongTitle: String? = null,
    val currentSongArtist: String? = null,
    val activeMediaAppPackage: String? = null
)
