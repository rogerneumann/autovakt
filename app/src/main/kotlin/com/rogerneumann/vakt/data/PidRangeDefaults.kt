package com.rogerneumann.vakt.data

object PidRangeDefaults {
    fun defaultRange(shortName: String): Pair<Float, Float> = when (shortName) {
        "SOC"          -> 0f to 100f
        "RPM"          -> 0f to 8000f
        "SPEED"        -> 0f to 200f
        "COOLANT_TEMP" -> -40f to 150f
        "THROTTLE"     -> 0f to 100f
        "LOAD"         -> 0f to 100f
        "FUEL"         -> 0f to 100f
        "PWR"          -> -150f to 150f
        "FUEL_RATE"    -> 0f to 20f
        "BOOST_PSI"    -> -5f to 30f
        else           -> 0f to 100f
    }

    fun defaultDisplayType(shortName: String): SlotDisplayType = when (shortName) {
        "SOC", "LOAD", "THROTTLE", "FUEL" -> SlotDisplayType.ARC
        "PWR", "FUEL_RATE", "BOOST_PSI"   -> SlotDisplayType.BAR
        else                               -> SlotDisplayType.NUMERIC
    }
}
