package com.rogerneumann.autovakt.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.rogerneumann.autovakt.db.TripDao
import com.rogerneumann.autovakt.db.AutoVaktDatabase
import com.rogerneumann.autovakt.util.CrashReporter
import com.rogerneumann.autovakt.util.LocalCrashReporter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Separate abstract module for @Binds (cannot coexist with @Provides in an object)
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    /**
     * Swap this binding to FirebaseCrashReporter when adding Crashlytics.
     * No other files need to change.
     */
    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: LocalCrashReporter): CrashReporter
}

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
    fun provideDatabase(@ApplicationContext context: Context): AutoVaktDatabase {
        return Room.databaseBuilder(
            context,
            AutoVaktDatabase::class.java,
            "autovakt_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideTripDao(db: AutoVaktDatabase): TripDao = db.tripDao()

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("autovakt_prefs", Context.MODE_PRIVATE)
    }
}
