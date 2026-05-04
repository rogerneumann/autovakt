package com.rogerneumann.vakt.data

import com.rogerneumann.vakt.db.TripDao
import com.rogerneumann.vakt.db.TripEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao
) {
    private var activeTripId: Long? = null

    /**
     * Starts a new trip recording. 
     * Resets the activeTripId to the new insertion.
     */
    suspend fun startNewTrip(vin: String, currentSoc: Float) {
        // End any existing dangling trip
        endCurrentTrip(currentSoc)

        val newTrip = TripEntity(
            vin = vin,
            startTime = System.currentTimeMillis(),
            startSoc = currentSoc,
            isManualTrigger = true
        )
        activeTripId = tripDao.insertTrip(newTrip)
    }

    /**
     * Updates the current active trip with rolling stats.
     */
    suspend fun updateActiveTrip(distance: Float, energyUsed: Float) {
        val id = activeTripId ?: return
        val trip = tripDao.getTripById(id) ?: return
        
        tripDao.updateTrip(trip.copy(
            distanceMiles = distance,
            energyKwhUsed = energyUsed
        ))
    }

    /**
     * Finalizes the current trip.
     */
    suspend fun endCurrentTrip(finalSoc: Float) {
        val id = activeTripId ?: return
        val trip = tripDao.getTripById(id) ?: return
        
        tripDao.updateTrip(trip.copy(
            endTime = System.currentTimeMillis(),
            endSoc = finalSoc
        ))
        activeTripId = null
    }

    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAllTrips()
}
