package com.rogerneumann.autovakt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/rogerneumann/autovakt/releases/latest"

    /**
     * Checks GitHub releases in the background. Calls [onUpdateAvailable] on the main thread
     * only when a tag other than [currentVersion] is found. Silent on any network error or when
     * no releases exist yet.
     */
    fun check(currentVersion: String, onUpdateAvailable: (latestVersion: String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val latest = Regex(""""tag_name"\s*:\s*"v([^"]+)"""")
                    .find(body)?.groupValues?.getOrNull(1) ?: return@launch

                if (latest != currentVersion) {
                    withContext(Dispatchers.Main) { onUpdateAvailable(latest) }
                }
            } catch (_: Exception) {
                // No network, repo has no releases yet, or rate-limited — ignore silently.
            }
        }
    }
}
