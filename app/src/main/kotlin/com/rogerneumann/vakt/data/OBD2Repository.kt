package com.rogerneumann.vakt.data

import com.rogerneumann.vakt.obd2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * Orchestrates the OBD2 polling loop, initialization, and data mapping.
 * Support for both live data and a "Demo Mode" for UI development.
 */
@Singleton
class OBD2Repository @Inject constructor(
    private val transport: ElmBluetoothTransport,
    private val queue: ElmCommandQueue,
    private val protocolHandler: GmProtocolHandler,
    private val tripRepository: TripRepository,
    private val profileManager: VehicleProfileManager
) {
    private val _liveData = MutableStateFlow(VaktLiveData())
    val liveData: StateFlow<VaktLiveData> = _liveData.asStateFlow()

    // FIX: Single persistent scope — never replaced, only pollingJob is cancelled/restarted.
    // Previously a new CoroutineScope was created on every start() call, leaking the old one.
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var isDemoMode = false
    private var vehicleProfile = VehicleProfile.DEFAULT
    private val customPids = mutableListOf<CustomPid>()
    
    // Accumulators for the active trip
    private var accumulatedEnergyKwh = 0f
    private var accumulatedDistanceMiles = 0f
    private var lastStatsUpdateTime = 0L

    /**
     * Adds a custom PID to the polling loop.
     */
    fun addCustomPid(pid: CustomPid) {
        customPids.add(pid)
    }

    /**
     * Starts the data flow. If [useDemoMode] is true, generates synthetic data.
     * Safe to call multiple times — cancels the previous polling job first.
     */
    fun start(useDemoMode: Boolean = false) {
        isDemoMode = useDemoMode
        pollingJob?.cancel()
        
        pollingJob = repositoryScope.launch {
            if (isDemoMode) {
                runDemoLoop()
            } else {
                runLiveLoop()
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
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

            // P1 FIX: Discover VIN and populate liveData
            val vin = protocolHandler.discoverVin()
            if (vin != null) {
                _liveData.value = _liveData.value.copy(vin = vin)
            }
            
            // Load active profile from Settings
            vehicleProfile = profileManager.getActiveProfile()
            
            // Populate custom PIDs from profile
            customPids.clear()
            customPids.addAll(vehicleProfile.customPids)
            
            _liveData.value = _liveData.value.copy(
                vehicleProfile = vehicleProfile,
                connectionState = ConnectionState.Connected
            )

            lastStatsUpdateTime = System.currentTimeMillis()
            accumulatedEnergyKwh = 0f
            accumulatedDistanceMiles = 0f

            while (coroutineContext.isActive) {
                val loopStartTime = System.currentTimeMillis()
                
                if (vehicleProfile.powertrain == PowertrainType.EV) {
                    pollEvMetrics()
                } else {
                    pollIceMetrics()
                }

                // Poll Custom PIDs (Torque/Generic)
                pollCustomPids()

                // Update Trip Stats (Energy/Distance integration)
                updateAccumulators(loopStartTime)
                
                delay(2000)
            }
        } catch (e: Exception) {
            _liveData.value = _liveData.value.copy(connectionState = ConnectionState.Error(e.message ?: "Unknown error"))
        }
    }

    private suspend fun pollCustomPids() {
        val results = _liveData.value.customPids.toMutableMap()
        
        for (pid in customPids) {
            try {
                // Set header if specified
                if (!pid.header.isNullOrEmpty()) {
                    queue.execute("ATSH ${pid.header}")
                }
                
                val response = queue.execute(pid.modeAndPid)
                val bytes = extractRawBytes(response, pid.modeAndPid)
                
                if (bytes != null) {
                    val value = PidFormulaParser.evaluate(pid.equation, bytes)
                    results[pid.shortName] = value
                }
            } catch (e: Exception) {
                // Skip failed PID
            }
        }
        
        _liveData.value = _liveData.value.copy(customPids = results)
    }

    /**
     * Helper to extract data bytes from an OBD2 response.
     * Skips the response header (e.g., 41 0C -> skips 41 0C).
     */
    private fun extractRawBytes(response: String, command: String): ByteArray? {
        val clean = response.replace(" ", "").trim().uppercase()
        val cmdClean = command.replace(" ", "").trim().uppercase()
        
        // Expected response prefix is (Mode + 0x40) + PID
        val mode = cmdClean.substring(0, 2)
        val pid = cmdClean.substring(2)
        val responseMode = (mode.toInt(16) + 0x40).toString(16).uppercase()
        val prefix = responseMode + pid

        if (!clean.startsWith(prefix)) return null
        
        val payloadHex = clean.substring(prefix.length)
        if (payloadHex.length % 2 != 0) return null
        
        return try {
            val bytes = ByteArray(payloadHex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = payloadHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            bytes
        } catch (e: Exception) {
            null
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
        val speedKmh = ObdParser.parseSpeedKmh(queue.execute("01 0D"))
        val speedMph = if (speedKmh != null) ObdParser.kmhToMph(speedKmh) else null
        
        // If it's a GM vehicle, we can use the high-speed optimized polling
        if (vehicleProfile.make == "Chevrolet" || vehicleProfile.make == "GMC") {
            val soc = protocolHandler.requestSoc()
            val voltage = protocolHandler.requestVoltage()
            val current = protocolHandler.requestCurrent()
            
            val power = if (voltage != null && current != null) {
                ObdParser.calculatePowerKw(voltage, current)
            } else null

            _liveData.value = _liveData.value.copy(
                soc = soc,
                powerKw = power,
                speedMph = speedMph ?: _liveData.value.speedMph
            )
        } else {
            // For other EVs, the data comes from Custom PIDs mapped to the customPids map in LiveData
            // We can map specific ShortNames to the core fields for the UI
            val custom = _liveData.value.customPids
            _liveData.value = _liveData.value.copy(
                soc = custom["SOC"] ?: _liveData.value.soc,
                powerKw = custom["PWR"] ?: _liveData.value.powerKw,
                speedMph = speedMph ?: _liveData.value.speedMph
            )
        }
    }

    private suspend fun pollIceMetrics() {
        // Standard OBD2 fallback
        val rpm = ObdParser.parseRpm(queue.execute("01 0C"))
        val speedKmh = ObdParser.parseSpeedKmh(queue.execute("01 0D"))
        val speedMph = if (speedKmh != null) ObdParser.kmhToMph(speedKmh) else null

        _liveData.value = _liveData.value.copy(
            rpm = rpm,
            speedMph = speedMph ?: _liveData.value.speedMph
        )
    }

    /**
     * Synthetic data loop for UI development.
     */
    private suspend fun runDemoLoop() {
        var currentSoc = 85.0f
        while (coroutineContext.isActive) {
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

}
