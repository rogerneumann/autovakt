package com.rogerneumann.autovakt

import android.app.Application
import android.util.Log
import com.rogerneumann.autovakt.util.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AutoVaktApplication : Application() {

    @Inject lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AUTOVAKT_CRASH", "Uncaught exception on thread ${thread.name}", throwable)
            // Persist crash to file before process dies so it's included in next log share
            runCatching { crashReporter.recordCrash(throwable) }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
