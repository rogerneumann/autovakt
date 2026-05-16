package com.rogerneumann.autovakt.util

/**
 * Abstraction over crash/error reporting backends.
 *
 * Current binding: [LocalCrashReporter] (logcat + file).
 * Future binding:  FirebaseCrashReporter (wraps Crashlytics — swap in AppModule, ~20 lines).
 *
 * Context keys set via [setKey] are forwarded to Crashlytics as custom keys when Firebase is live.
 */
interface CrashReporter {
    fun recordCrash(throwable: Throwable)
    fun recordNonFatal(throwable: Throwable, message: String? = null)
    fun setKey(key: String, value: String)
    fun log(message: String)
}
