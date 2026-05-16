package com.rogerneumann.autovakt.util

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local [CrashReporter] implementation.
 *
 * - Crashes: saved to file via [LogShareManager] so they survive the process death.
 * - Non-fatals and logs: written to logcat under tag "AutoVaktDiag".
 * - Custom keys: logged as structured key=value entries.
 *
 * To migrate to Firebase Crashlytics, implement [CrashReporter] wrapping
 * `FirebaseCrashlytics.getInstance()` and rebind in AppModule — this class is unused.
 */
@Singleton
class LocalCrashReporter @Inject constructor(
    private val logShareManager: LogShareManager
) : CrashReporter {

    override fun recordCrash(throwable: Throwable) {
        Log.e(TAG, "Fatal crash recorded", throwable)
        logShareManager.saveCrash(throwable)
    }

    override fun recordNonFatal(throwable: Throwable, message: String?) {
        Log.w(TAG, message ?: "Non-fatal exception", throwable)
    }

    override fun setKey(key: String, value: String) {
        Log.d(TAG, "ctx [$key] = $value")
    }

    override fun log(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "AutoVaktDiag"
    }
}
