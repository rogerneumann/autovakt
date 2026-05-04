package com.rogerneumann.vakt.data

import com.rogerneumann.vakt.obd2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Orchestrates the OBD2 polling loop, initialization, and data mapping.
 * Support for both live data and a "Demo Mode" for UI development.
 */
class OBD2Repository(
    private val transport: ElmBluetoothTransport,
    private val queue: ElmCommandQueue
) {
    private val _liveData = MutableStateFlow(VaktLiveData())
    val liveData: StateFlow<VaktLiveData> = _liveData.asStateFlow()

    private var pollingJob: Job? = null
    private var isDemoMode = false

    /**
     * Starts the data flow. If [useDemoMode] is true, generates synthetic data.
     */
    fun start(useDemoMode: Boolean = false) {
        isDemoMode = useDemoMode
        pollingJob?.cancel()
        
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            if (isDemoMode) {
                runDemoLoop()
            } else {
                runLiveLoop()
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        transport.disconnect()
    }

    /**
     * Live polling loop for real ELM327 communication.
     */
    private suspend fun runLiveLoop() {
        // 1. Wait for connection
        if (transport.connectionState.value !is ConnectionState.Connected) {
            return
        }

        try {
            // 2. Handshake / Init
            queue.execute("ATZ") // Reset
            queue.execute("ATE0") // Echo off
            queue.execute("ATI") // Device ID
            
            // 3. VIN Discovery (Tiered support)
            val vinResponse = queue.execute("09 02", timeoutMs = 5000)
            val isBolt = vinResponse.contains("1G1") // Simplified Bolt detection

            // 4. Polling Cycle
            while (isActive) {
                // Poll Hero Metrics (Fast)
                val soc = pollSoc()
                val power = pollPower()
                
                _liveData.value = _liveData.value.copy(
                    soc = soc,
                    powerKw = power,
                    connectionState = ConnectionState.Connected
                )
                
                delay(2000) // 2s cycle for hero metrics
            }
        } catch (e: Exception) {
            _liveData.value = _liveData.value.copy(connectionState = ConnectionState.Error(e.message ?: "Unknown error"))
        }
    }

    /**
     * Synthetic data loop for UI development.
     */
    private suspend fun runDemoLoop() {
        var currentSoc = 85.0f
        while (isActive) {
            // Simulate driving: SOC goes down, Power fluctuations
            currentSoc -= 0.01f
            if (currentSoc < 0) currentSoc = 100f
            
            val simulatedPower = Random.nextFloat() * 50f - 10f // -10kW to +40kW
            
            _liveData.value = _liveData.value.copy(
                soc = currentSoc,
                powerKw = simulatedPower,
                speedMph = 45f + Random.nextInt(-5, 5),
                connectionState = ConnectionState.Connected,
                currentSongTitle = "Demo Mode Active",
                currentSongArtist = "Vakt UI Test"
            )
            delay(2000)
        }
    }

    private suspend fun pollSoc(): Float? {
        // Placeholder for actual PID logic
        return 87.0f 
    }

    private suspend fun pollPower(): Float? {
        // Placeholder for actual PID logic
        return 32.5f
    }
}
