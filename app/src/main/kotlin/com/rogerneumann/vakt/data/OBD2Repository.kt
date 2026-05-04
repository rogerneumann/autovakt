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
    private val queue: ElmCommandQueue,
    private val protocolHandler: GmProtocolHandler,
    private val tripRepository: TripRepository
) {
    private val _liveData = MutableStateFlow(VaktLiveData())
    val liveData: StateFlow<VaktLiveData> = _liveData.asStateFlow()

    private var pollingJob: Job? = null
    private var isDemoMode = false
    private var vehicleProfile = VehicleProfile.DEFAULT
    
    // Accumulators for the active trip
    private var accumulatedEnergyKwh = 0f
    private var accumulatedDistanceMiles = 0f
    private var lastStatsUpdateTime = 0L

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
        if (transport.connectionState.value !is ConnectionState.Connected) {
            return
        }

        try {
            queue.execute("ATZ")
            queue.execute("ATE0")
            
            val vin = protocolHandler.discoverVin()
            vehicleProfile = VehicleProfileManager.getProfileForVin(vin)
            
            _liveData.value = _liveData.value.copy(
                vin = vin,
                connectionState = ConnectionState.Connected
            )

            lastStatsUpdateTime = System.currentTimeMillis()
            accumulatedEnergyKwh = 0f
            accumulatedDistanceMiles = 0f

            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                
                if (vehicleProfile.type == VehicleType.EV) {
                    pollEvMetrics()
                } else {
                    pollIceMetrics()
                }

                // Update Trip Stats (Energy/Distance integration)
                updateAccumulators(loopStartTime)
                
                delay(2000)
            }
        } catch (e: Exception) {
            _liveData.value = _liveData.value.copy(connectionState = ConnectionState.Error(e.message ?: "Unknown error"))
        }
    }

    private fun updateAccumulators(currentTime: Long) {
        val dtSeconds = (currentTime - lastStatsUpdateTime) / 1000f
        if (dtSeconds <= 0) return

        val currentPower = _liveData.value.powerKw ?: 0f
        val currentSpeed = _liveData.value.speedMph ?: 0f

        // Integration: Energy = Power * Time
        val energyDelta = (currentPower * dtSeconds) / 3600f
        accumulatedEnergyKwh += energyDelta

        // Integration: Distance = Speed * Time
        val distanceDelta = (currentSpeed * dtSeconds) / 3600f
        accumulatedDistanceMiles += distanceDelta

        lastStatsUpdateTime = currentTime

        // Update LiveData for UI
        _liveData.value = _liveData.value.copy(
            tripEnergyKwh = accumulatedEnergyKwh,
            tripDistanceMiles = accumulatedDistanceMiles
        )

        // Periodically persist to DB (every 30s)
        if (System.currentTimeMillis() % 30000 < 2500) {
            CoroutineScope(Dispatchers.IO).launch {
                tripRepository.updateActiveTrip(accumulatedDistanceMiles, accumulatedEnergyKwh)
            }
        }
    }

    private suspend fun pollEvMetrics() {
        val soc = pollSoc()
        val power = pollPower()
        
        _liveData.value = _liveData.value.copy(
            soc = soc,
            powerKw = power
        )
    }

    private suspend fun pollIceMetrics() {
        // Standard OBD2 fallback
        val rpm = ObdParser.parseRpm(queue.execute("01 0C"))
        _liveData.value = _liveData.value.copy(
            speedMph = rpm?.toFloat() ?: 0f // Placeholder
        )
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
        val response = queue.execute("01 2F") // Example Fuel Level PID for SOC
        return ObdParser.parseSoc(response)
    }

    private suspend fun pollPower(): Float? {
        // Example: In a real Bolt implementation, this would involve multiple PIDs
        // For now, we use the ObdParser helper with fixed values to demonstrate the flow
        return ObdParser.calculatePowerKw(380f, 45f) 
    }
}
