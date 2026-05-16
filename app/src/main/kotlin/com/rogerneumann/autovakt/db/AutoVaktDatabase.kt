package com.rogerneumann.autovakt.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, DtcEntity::class], version = 1, exportSchema = false)
abstract class AutoVaktDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
