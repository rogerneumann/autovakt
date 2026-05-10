package com.rogerneumann.vakt.data

import com.rogerneumann.vakt.obd2.ConnectionState
import com.rogerneumann.vakt.obd2.ElmCommandQueue
import com.rogerneumann.vakt.obd2.GmProtocolHandler
import com.rogerneumann.vakt.obd2.ObdParser
import com.rogerneumann.vakt.obd2.PidFormulaParser
import com.rogerneumann.vakt.obd2.TransportDelegate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class OBD2Repository @Inject constructor(
    private val transport: TransportDelegate,
    private val queue: ElmCommandQueue,
    private val protocolHandler: GmProtocolHandler,
    private val tripRepository: TripRepository,
    private val profileManager: VehicleProfileManager,
    private val profileHub: VehicleProfileHub
) {
    private val _liveData = MutableStateFlow(VaktLiveData())
    val liveData: StateFlow<VaktLiveData> = _liveData.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var isDemoMode = false
    private var vehicleProfile = VehicleProfile.DEFAULT

    private var accumulatedEnergyKwh = 0f
    private var accumulatedDistanceMiles = 0f
    private var lastStatsUpdateTime = 0L
    private var lastPersistTime = 0L

    suspend fun startManualTrip() {
        tripRepository.startManualTrip(
            vin = _liveData.value.vin ?: "",
            startSoc = _liveData.value.soc ?: 0f
        )
    }

    fun start(useDemoMode: Boolean = false) {
        isDemoMode = useDemoMode
        pollingJob?.cancel()
        pollingJob = repositoryScope.launch {
            if (isDemoMode) runDemoLoop() else runLiveLoop()
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        transport.disconnect()
    }

    private suspend fun runLiveLoop() {
        if (transport.connectionState.value !is ConnectionState.Connected) return

        try {
            queue.execute("ATZ")
            queue.execute("ATE0")

            val vin = protocolHandler.discoverVin()
            if (vin != null) {
                _liveData.value = _liveData.value.copy(vin = vin)
            }

            // Auto-select profile by VIN if unambiguous
            vehicleProfile = resolveProfile(vin)

            // Run profile-specific ELM init commands
            for (cmd in vehicleProfile.initCommands) {
                queue.execute(cmd)
            }

            _liveData.value = _liveData.value.copy(
                vehicleProfile = vehicleProfile,
                connectionState = ConnectionState.Connected
            )

            lastStatsUpdateTime = System.currentTimeMillis()
            lastPersistTime = System.currentTimeMillis()
            accumulatedEnergyKwh = 0f
            accumulatedDistanceMiles = 0f

            while (coroutineContext.isActive) {
                pollCustomPids()
                updateAccumulators()
                delay(2000)
            }
        } catch (e: Exception) {
            _liveData.value = _liveData.value.copy(
                connectionState = ConnectionState.Error(e.message ?: "Unknown error")
            )
        }
    }

    private fun resolveProfile(vin: String?): VehicleProfile {
        if (vin != null) {
            val matches = profileHub.findProfileByVin(vin)
            if (matches.size == 1) return matches.first()
        }
        return profileManager.getActiveProfile()
    }

    private suspend fun pollCustomPids() {
        val profile = vehicleProfile
        val updatedCustom = _liveData.value.customPids.toMutableMap()

        // Track these for derived power calculation
        var hvVoltage: Float? = null
        var hvCurrent: Float? = null

        for (pid in profile.customPids) {
            try {
                if (!pid.header.isNullOrEmpty()) {
                    queue.execute("ATSH ${pid.header}")
                }
                val response = queue.execute(pid.modeAndPid)
                val bytes = extractRawBytes(response, pid.modeAndPid) ?: continue
                val value = PidFormulaParser.evaluate(pid.equation, bytes, pid.nonLinearMap)
                updatedCustom[pid.shortName] = value

                // Track specific shortNames for derived calculations
                when (pid.shortName) {
                    "HV_V" -> hvVoltage = value
                    "HV_I" -> hvCurrent = value
                }
            } catch (_: Exception) { /* skip failed PID */ }
        }

        // Map standard shortNames to core LiveData fields
        val speedKmh = updatedCustom["SPEED"]
        val speedMph = if (speedKmh != null) ObdParser.kmhToMph(speedKmh) else null
        val power = updatedCustom["PWR"]
            ?: if (hvVoltage != null && hvCurrent != null) hvVoltage * hvCurrent / 1000f else null

        _liveData.value = _liveData.value.copy(
            soc          = updatedCustom["SOC"]       ?: _liveData.value.soc,
            powerKw      = power                       ?: _liveData.value.powerKw,
            speedMph     = speedMph                    ?: _liveData.value.speedMph,
            rpm          = updatedCustom["RPM"]?.toInt() ?: _liveData.value.rpm,
            hvVoltage    = updatedCustom["HV_V"]      ?: _liveData.value.hvVoltage,
            hvCurrent    = updatedCustom["HV_I"]      ?: _liveData.value.hvCurrent,
            battTempMaxC = updatedCustom["BATT_T_MAX"] ?: _liveData.value.battTempMaxC,
            battTempMinC = updatedCustom["BATT_T_MIN"] ?: _liveData.value.battTempMinC,
            engineLoad   = updatedCustom["LOAD"]      ?: _liveData.value.engineLoad,
            customPids   = updatedCustom
        )

        // Auto-start trip when speed exceeds 3 mph and no active trip is running
        val currentSpeed = _liveData.value.speedMph ?: 0f
        if (currentSpeed > 3f && tripRepository.activeTripId == null) {
            tripRepository.startNewTrip(
                vin = _liveData.value.vin ?: "",
                startSoc = _liveData.value.soc ?: 0f
            )
        }
    }

    private fun extractRawBytes(response: String, command: String): ByteArray? {
        val clean = response.replace(" ", "").trim().uppercase()
        val cmdClean = command.replace(" ", "").trim().uppercase()

        val mode = cmdClean.substring(0, 2)
        val pid = cmdClean.substring(2)
        val responseMode = (mode.toInt(16) + 0x40).toString(16).uppercase()
        val prefix = responseMode + pid

        if (!clean.startsWith(prefix)) return null
        val payloadHex = clean.substring(prefix.length)
        if (payloadHex.length % 2 != 0) return null

        return try {
            ByteArray(payloadHex.length / 2) { i ->
                payloadHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) { null }
    }

    private fun updateAccumulators() {
        val now = System.currentTimeMillis()
        val dtSeconds = (now - lastStatsUpdateTime) / 1000f
        if (dtSeconds <= 0) return

        val currentPower = _liveData.value.powerKw ?: 0f
        val currentSpeed = _liveData.value.speedMph ?: 0f

        accumulatedEnergyKwh += (currentPower * dtSeconds) / 3600f
        accumulatedDistanceMiles += (currentSpeed * dtSeconds) / 3600f
        lastStatsUpdateTime = now

        _liveData.value = _liveData.value.copy(
            tripEnergyKwh    = accumulatedEnergyKwh,
            tripDistanceMiles = accumulatedDistanceMiles
        )

        // Fixed 30s persist — no more modulo race condition
        if (now - lastPersistTime >= 30_000L) {
            lastPersistTime = now
            repositoryScope.launch {
                tripRepository.updateActiveTrip(accumulatedDistanceMiles, accumulatedEnergyKwh)
            }
        }
    }

    private suspend fun runDemoLoop() {
        var currentSoc = 85.0f
        var demoPower = 0f
        var demoSpeed = 45f

        while (coroutineContext.isActive) {
            currentSoc -= 0.01f
            if (currentSoc < 0) currentSoc = 100f
            demoPower = (demoPower + Random.nextFloat() * 10f - 5f).coerceIn(-30f, 80f)
            demoSpeed = (demoSpeed + Random.nextFloat() * 4f - 2f).coerceIn(0f, 80f)

            _liveData.value = _liveData.value.copy(
                soc              = currentSoc,
                powerKw          = demoPower,
                speedMph         = demoSpeed,
                hvVoltage        = 380f + Random.nextFloat() * 20f,
                hvCurrent        = demoPower * 1000f / 390f,
                connectionState  = ConnectionState.Connected,
                currentSongTitle = "Demo Mode Active",
                currentSongArtist = "Vakt UI Test"
            )
            delay(2000)
        }
    }
}
