package com.rogerneumann.vakt.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTripById(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Delete
    suspend fun deleteTrip(trip: TripEntity)

    // DTC Operations
    @Insert
    suspend fun insertDtc(dtc: DtcEntity)

    @Query("SELECT * FROM dtc_logs WHERE vin = :vin ORDER BY timestamp DESC")
    fun getDtcHistoryForVin(vin: String): Flow<List<DtcEntity>>
}
