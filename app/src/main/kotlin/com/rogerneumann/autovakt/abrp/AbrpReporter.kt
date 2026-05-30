package com.rogerneumann.autovakt.abrp

import android.content.Context
import android.util.Log
import com.rogerneumann.autovakt.data.AutoVaktLiveData
import com.rogerneumann.autovakt.obd2.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AbrpReporter"
private const val PREFS_NAME = "autovakt_prefs"
private const val KEY_TOKEN = "abrp_token"
private const val KEY_BASE_URL = "abrp_base_url"
private const val DEFAULT_BASE_URL = "https://api.iternio.com/1/tlm/send"
private const val SEND_INTERVAL_MS = 5000L

data class AbrpSendStatus(
    val sentCount: Int = 0,
    val lastSentMs: Long? = null,
    val lastError: String? = null,
    val lastErrorMs: Long? = null
)

@Singleton
class AbrpReporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var reportingJob: Job? = null

    private val _status = MutableStateFlow(AbrpSendStatus())
    val status: StateFlow<AbrpSendStatus> = _status.asStateFlow()

    // ── Token ────────────────────────────────────────────────────────────────

    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    // ── Base URL ─────────────────────────────────────────────────────────────

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }

    // ── Charging logic (internal for direct testability) ──────────────────────

    internal fun isCharging(speedKmh: Float, powerKw: Float): Int =
        if (speedKmh < 2.0f && powerKw < -0.5f) 1 else 0

    // ── Send ─────────────────────────────────────────────────────────────────

    suspend fun send(data: AutoVaktLiveData) {
        val token = getToken()
        if (token.isBlank()) {
            Log.d(TAG, "skip — no token configured")
            return
        }
        val soc = data.soc ?: run {
            Log.d(TAG, "skip — SOC is null (state=${data.connectionState})")
            return
        }

        val speedKmh = (data.speedMph ?: 0f) * 1.60934f
        val powerKw = data.powerKw ?: 0f
        val isCharging = isCharging(speedKmh, powerKw)

        val params = buildMap<String, String> {
            put("utc", (System.currentTimeMillis() / 1000).toString())
            put("soc", "%.1f".format(soc))
            put("speed", "%.1f".format(speedKmh))
            put("power", "%.2f".format(powerKw))
            put("is_charging", isCharging.toString())
            data.battTempMaxC?.let { put("batt_temp", "%.1f".format(it)) }
            data.hvVoltage?.let { put("voltage", "%.1f".format(it)) }
            data.hvCurrent?.let { put("current", "%.2f".format(it)) }
        }

        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val urlString = "${getBaseUrl()}?token=${URLEncoder.encode(token, "UTF-8")}"

        withContext(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(body)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val msg = "HTTP $responseCode"
                    Log.w(TAG, "Unexpected response: $msg")
                    _status.value = _status.value.copy(lastError = msg, lastErrorMs = System.currentTimeMillis())
                } else {
                    Log.d(TAG, "send OK — soc=%.1f, speed=%.1f km/h, is_charging=$isCharging".format(soc, speedKmh))
                    _status.value = _status.value.copy(
                        sentCount = _status.value.sentCount + 1,
                        lastSentMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }
                conn.disconnect()
            } catch (e: Exception) {
                val msg = e.message ?: "unknown error"
                Log.w(TAG, "send failed: $msg")
                _status.value = _status.value.copy(lastError = msg, lastErrorMs = System.currentTimeMillis())
            }
        }
    }

    // ── Reporting loop ────────────────────────────────────────────────────────

    fun startReporting(scope: CoroutineScope, liveData: StateFlow<AutoVaktLiveData>) {
        reportingJob?.cancel()
        reportingJob = scope.launch {
            val ticker = flow {
                while (true) {
                    emit(liveData.value)
                    delay(SEND_INTERVAL_MS)
                }
            }
            ticker.collect { data ->
                if (data.connectionState is ConnectionState.Connected) {
                    send(data)
                }
            }
        }
    }
}
