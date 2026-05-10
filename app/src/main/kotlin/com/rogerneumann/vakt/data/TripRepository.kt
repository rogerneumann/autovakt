package com.rogerneumann.vakt.data

import android.content.Context
import com.rogerneumann.vakt.db.TripDao
import com.rogerneumann.vakt.db.TripEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripDao: TripDao
) {
    private val prefs = context.getSharedPreferences("vakt_trips", Context.MODE_PRIVATE)

    var activeTripId: Long? = null
        private set

    init {
        val saved = prefs.getLong("active_trip_id", -1L)
        if (saved >= 0) activeTripId = saved
    }

    suspend fun startNewTrip(vin: String, startSoc: Float) {
        endCurrentTrip(startSoc)

        val newTrip = TripEntity(
            vin = vin,
            startTime = System.currentTimeMillis(),
            startSoc = startSoc,
            isManualTrigger = false
        )
        activeTripId = tripDao.insertTrip(newTrip)
        prefs.edit().putLong("active_trip_id", activeTripId!!).apply()
    }

    suspend fun startManualTrip(vin: String, startSoc: Float) {
        endCurrentTrip(startSoc)

        val newTrip = TripEntity(
            vin = vin,
            startTime = System.currentTimeMillis(),
            startSoc = startSoc,
            isManualTrigger = true
        )
        activeTripId = tripDao.insertTrip(newTrip)
        prefs.edit().putLong("active_trip_id", activeTripId!!).apply()
    }

    suspend fun updateActiveTrip(distance: Float, energyUsed: Float) {
        val id = activeTripId ?: return
        val trip = tripDao.getTripById(id) ?: return
        tripDao.updateTrip(trip.copy(distanceMiles = distance, energyKwhUsed = energyUsed))
    }

    suspend fun endCurrentTrip(finalSoc: Float) {
        val id = activeTripId ?: return
        val trip = tripDao.getTripById(id) ?: return
        tripDao.updateTrip(trip.copy(endTime = System.currentTimeMillis(), endSoc = finalSoc))
        activeTripId = null
        prefs.edit().remove("active_trip_id").apply()
    }

    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAllTrips()
}
