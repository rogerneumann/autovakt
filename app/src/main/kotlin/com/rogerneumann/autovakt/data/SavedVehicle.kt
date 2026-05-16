package com.rogerneumann.autovakt.data

data class SavedVehicle(
    val key: String,           // the full prefix used in SharedPrefs
    val vin: String?,
    val adapterMac: String?,
    val profileId: String,
    val autoLabel: String,     // "{make} {model} ({year}) ···{last4}" or "Unknown ···{last4mac}"
    val userLabel: String?,    // null = use autoLabel
    val lastConnected: Long    // epoch ms
) {
    val displayLabel: String get() = userLabel ?: autoLabel
}
