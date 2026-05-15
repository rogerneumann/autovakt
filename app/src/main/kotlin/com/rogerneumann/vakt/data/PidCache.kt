package com.rogerneumann.vakt.data

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PidCache @Inject constructor() {
    data class Entry(val raw: String, val timestampMs: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun put(pidKey: String, raw: String) {
        cache[pidKey] = Entry(raw, System.currentTimeMillis())
    }

    fun get(pidKey: String, maxAgeMs: Long = 3000L): String? {
        val e = cache[pidKey] ?: return null
        return if (System.currentTimeMillis() - e.timestampMs <= maxAgeMs) e.raw else null
    }

    fun clear() = cache.clear()
}
