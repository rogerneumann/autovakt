package com.rogerneumann.vakt.data

/**
 * Manages vehicle-specific profiles based on VIN identification.
 * Automatically loads the correct PID set and UI configurations.
 */
object VehicleProfileManager {

    /**
     * Maps a VIN to a VehicleProfile.
     * 1G1 = Chevrolet USA
     * 1G1F... = Bolt EV/EUV
     */
    fun getProfileForVin(vin: String?): VehicleProfile {
        if (vin == null) return VehicleProfile.DEFAULT

        return when {
            vin.startsWith("1G1FY") || vin.startsWith("1G1FZ") -> {
                VehicleProfile(
                    name = "Chevrolet Bolt EUV",
                    manufacturer = "Chevrolet",
                    type = VehicleType.EV,
                    supportedPids = listOf("SOC", "POWER", "BATT_TEMP", "CELL_VOLTS")
                )
            }
            vin.startsWith("1G1") -> {
                VehicleProfile(
                    name = "General Motors Vehicle",
                    manufacturer = "GM",
                    type = VehicleType.UNKNOWN,
                    supportedPids = listOf("RPM", "SPEED", "TEMP")
                )
            }
            else -> VehicleProfile.DEFAULT
        }
    }
}

data class VehicleProfile(
    val name: String,
    val manufacturer: String,
    val type: VehicleType,
    val supportedPids: List<String>
) {
    companion object {
        val DEFAULT = VehicleProfile("Unknown Vehicle", "Generic", VehicleType.UNKNOWN, emptyList())
    }
}

enum class VehicleType {
    EV, ICE, HYBRID, UNKNOWN
}
