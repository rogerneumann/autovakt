package com.rogerneumann.vakt.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.room.Room
import com.rogerneumann.vakt.db.TripDao
import com.rogerneumann.vakt.db.VaktDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBluetoothAdapter(@ApplicationContext context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VaktDatabase {
        return Room.databaseBuilder(
            context,
            VaktDatabase::class.java,
            "vakt_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTripDao(db: VaktDatabase): TripDao {
        return db.tripDao()
    }
}
