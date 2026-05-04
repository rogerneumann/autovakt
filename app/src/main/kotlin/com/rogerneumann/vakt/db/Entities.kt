package com.rogerneumann.vakt.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single driving session or "Run".
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vin: String,
    val startTime: Long,
    val endTime: Long? = null,
    val startSoc: Float,
    val endSoc: Float? = null,
    val distanceMiles: Float = 0f,
    val energyKwhUsed: Float = 0f,
    val startOdometer: Float? = null,
    val endOdometer: Float? = null,
    val isManualTrigger: Boolean = false
)

/**
 * Stores Diagnostic Trouble Codes (DTCs) and their history.
 */
@Entity(tableName = "dtc_logs")
data class DtcEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vin: String,
    val timestamp: Long,
    val code: String,
    val description: String? = null,
    val isCleared: Boolean = false
)
