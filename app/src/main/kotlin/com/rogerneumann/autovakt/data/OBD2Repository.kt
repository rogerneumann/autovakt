package com.rogerneumann.autovakt.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rogerneumann.autovakt.media.MediaRemoteManager
import com.rogerneumann.autovakt.obd2.ConnectionState
import com.rogerneumann.autovakt.obd2.ElmCommandQueue
import com.rogerneumann.autovakt.obd2.GmProtocolHandler
import com.rogerneumann.autovakt.obd2.ObdParser
import com.rogerneumann.autovakt.obd2.PidFormulaParser
import com.rogerneumann.autovakt.obd2.TransportDelegate
import com.rogerneumann.autovakt.obd2.AutoVaktBridgeServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "AutoVaktOBD"

@Singleton
class OBD2Repository @Inject constructor(
    private val transport: TransportDelegate,
    private val queue: ElmCommandQueue,
    private val protocolHandler: GmProtocolHandler,
    private val tripRepository: TripRepository,
    private val profileManager: VehicleProfileManager,
    private val profileHub: VehicleProfileHub,
    private val vehicleLayoutManager: VehicleLayoutManager,
    private val pidCache: PidCache,
    private val bridgeServer: AutoVaktBridgeServer,
    private val mediaRemoteManager: MediaRemoteManager
) {
    private val _liveData = MutableStateFlow(AutoVaktLiveData())
    val liveData: StateFlow<AutoVaktLiveData> = _liveData.asStateFlow()

    private val _layoutSuggestion = MutableStateFlow<String?>(null)
    val layoutSuggestion: StateFlow<String?> = _layoutSuggestion.asStateFlow()

    private val _currentLayoutKey = MutableStateFlow("gauge_layout_global")
    val currentLayoutKey: StateFlow<String> = _currentLayoutKey.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var isDemoMode = false
    private var vehicleProfile = VehicleProfile.DEFAULT

    private var previousVin: String? = null
    private var previousPowertrain: PowertrainType? = null

    private var accumulatedEnergyKwh = 0f
    private var accumulatedDistanceMiles = 0f
    private var accumulatedFuelGallons = 0f
    private var lastStatsUpdateTime = 0L
    private var lastPersistTime = 0L

    // Rolling window for instant efficiency smoothing (size driven by user preference later)
    private val smoothingWindowSize: Int
        get() = 5  // default 5 samples × 2 s = 10 s; key "smoothing_window_size" reserved for Settings
    private val efficiencySamples = ArrayDeque<Float>()

    private fun addEfficiencySample(v: Float) {
        efficiencySamples.addLast(v)
        while (efficiencySamples.size > smoothingWindowSize) efficiencySamples.removeFirst()
    }

    private fun smoothedInstantEfficiency(): Float? =
        if (efficiencySamples.isEmpty()) null else efficiencySamples.average().toFloat()

    suspend fun startManualTrip() {
        tripRepository.startManualTrip(
            vin = _liveData.value.vin ?: "",
            startSoc = _liveData.value.soc ?: 0f
        )
    }

    fun start(useDemoMode: Boolean = false) {
        isDemoMode = useDemoMode
        pollingJob?.cancel()
        // Set Connecting immediately so the watchdog doesn't see Error and cancel this attempt
        if (!useDemoMode) {
            _liveData.value = _liveData.value.copy(connectionState = ConnectionState.Connecting)
        }
        pollingJob = repositoryScope.launch {
            if (isDemoMode) runDemoLoop() else runLiveLoop()
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        transport.disconnect()
        // Reset live data so the UI transitions to Disconnected immediately and
        // all stale gauge values are cleared rather than frozen on screen.
        _liveData.value = AutoVaktLiveData()
        accumulatedEnergyKwh = 0f
        accumulatedDistanceMiles = 0f
        accumulatedFuelGallons = 0f
        efficiencySamples.clear()
    }

    private suspend fun runLiveLoop() {
        // BLE GATT setup (service discovery + MTU) can take several seconds after connect()
        // returns. Wait up to 20s for the transport to reach Connected before starting.
        val deadline = System.currentTimeMillis() + 20_000L
        while (transport.connectionState.value !is ConnectionState.Connected) {
            if (transport.connectionState.value is ConnectionState.Error
                || System.currentTimeMillis() > deadline
            ) {
                _liveData.value = _liveData.value.copy(
                    connectionState = ConnectionState.Error("Connection timed out")
                )
                return
            }
            delay(300)
        }

        try {
            queue.execute("ATZ", 5000L)  // reset takes up to ~2s on ELM327; default 2000ms too tight
            delay(500L)                   // let ELM fully settle before first post-reset command
            queue.execute("ATE0")
            queue.execute("ATH0")   // headers off — responses begin with service byte, not CAN header
            queue.execute("ATL0")   // linefeeds off — cleaner response framing across all adapters
            queue.execute("ATS0")   // spaces off — our parser handles both; this is faster and standard
            // ATCAF1: required — strips ISO-TP framing so responses start with the service byte.
            // ATAL: required — allow long (multi-frame) responses; default truncates compound PIDs.
            // These two run on every connect, regardless of profile.
            queue.execute("ATCAF1")
            queue.execute("ATAL")

            val vin = protocolHandler.discoverVin()
            if (vin != null) {
                _liveData.value = _liveData.value.copy(vin = vin)
            }

            // Auto-select profile by VIN if unambiguous
            vehicleProfile = resolveProfile(vin)

            // Resolve layout key and record this connection
            val adapterMac = null  // TODO: expose from TransportDelegate in a future block
            val layoutKey = vehicleLayoutManager.resolveKey(vin, adapterMac, vehicleProfile.id)
            vehicleLayoutManager.recordConnection(layoutKey, vehicleProfile.id, vin, adapterMac, vehicleProfile)
            _currentLayoutKey.value = layoutKey

            // Detect powertrain class change for new VIN
            val isNewVin = vin != null && vin != previousVin
            val samePowertrain = previousPowertrain == null || previousPowertrain == vehicleProfile.powertrain
            if (isNewVin && previousVin != null && samePowertrain) {
                _layoutSuggestion.value = "New vehicle detected. Customise your dashboard layout in Settings."
            }
            previousVin = vin
            previousPowertrain = vehicleProfile.powertrain

            // Poll for stored DTCs before profile init commands run.
            // If a profile opens a UDS extended session (1003), DTCs must be polled
            // before that so nothing runs between session open and the first gated DID.
            pollAndSaveDtcs(vin ?: "")

            // Run profile-specific ELM init commands
            for (cmd in vehicleProfile.initCommands) {
                val initResp = queue.execute(cmd)
                Log.d(TAG, "initCmd '$cmd' → '${initResp.trim().take(40)}'")
            }
            // Give the ECU time to settle into the new diagnostic session
            delay(200L)

            _liveData.value = _liveData.value.copy(
                vehicleProfile = vehicleProfile,
                connectionState = ConnectionState.Connected
            )

            lastStatsUpdateTime = System.currentTimeMillis()
            lastPersistTime = System.currentTimeMillis()
            accumulatedEnergyKwh = 0f
            accumulatedDistanceMiles = 0f
            accumulatedFuelGallons = 0f
            efficiencySamples.clear()

            while (coroutineContext.isActive) {
                pollCustomPids()
                updateAccumulators()
                delay(2000)
            }
        } catch (e: CancellationException) {
            throw e  // structured concurrency: let intentional stop() cancel cleanly, not as Error
        } catch (e: Exception) {
            Log.e(TAG, "runLiveLoop error: ${e.message}", e)
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

    // Returns the CAN header that precedes "1003" in initCommands, or null if none.
    private fun udsKeepaliveHeader(): String? {
        val cmds = vehicleProfile.initCommands
        val idx = cmds.indexOf("1003")
        if (idx <= 0) return null
        val prev = cmds[idx - 1]
        return if (prev.startsWith("ATSH ")) prev.removePrefix("ATSH ").trim() else null
    }

    private suspend fun pollCustomPids() {
        if (transport.connectionState.value !is ConnectionState.Connected) {
            throw Exception("Transport disconnected")
        }
        val profile = vehicleProfile
        val updatedCustom = _liveData.value.customPids.toMutableMap()

        // Re-open UDS extended diagnostic session every poll cycle.
        // The Bolt EUV BMS returns NRC 0x12 (subFunctionNotSupported) for 3E00 —
        // this ECU does not support TesterPresent. Re-sending 1003 is safe and cheap.
        udsKeepaliveHeader()?.let { hdr ->
            try {
                queue.execute("ATSH $hdr")
                val reopenResp = queue.execute("1003")
                Log.d(TAG, "UDS session reopen → '${reopenResp.trim().take(40)}'")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.d(TAG, "UDS 1003 reopen failed: ${e.message}") }
        }

        // Track these for derived power calculation
        var hvVoltage: Float? = null
        var hvCurrent: Float? = null

        // PIDs actively requested by bridge clients (union with display-slot PIDs)
        val bridgePids = bridgeServer.bridgeRequestedPids

        for (pid in profile.customPids) {
            if (transport.connectionState.value !is ConnectionState.Connected) {
                throw Exception("Transport disconnected during poll")
            }
            try {
                // Skip if a very fresh cache entry exists and this isn't a bridge-demanded PID
                if (pidCache.get(pid.shortName, 2000L) != null && !bridgePids.contains(pid.shortName)) {
                    continue
                }
                if (!pid.header.isNullOrEmpty()) {
                    queue.execute("ATSH ${pid.header}")
                }
                val response = queue.execute(pid.modeAndPid)
                val bytes = extractRawBytes(response, pid.modeAndPid)
                if (bytes == null) {
                    Log.d(TAG, "PID ${pid.shortName}: parse failed — raw='${response.trim().take(60)}'")
                    continue
                }
                val value = PidFormulaParser.evaluate(pid.equation, bytes, pid.nonLinearMap)
                updatedCustom[pid.shortName] = value

                // Write raw response to cache for bridge consumers
                pidCache.put(pid.shortName, response)

                // Track specific shortNames for derived calculations
                when (pid.shortName) {
                    "HV_V" -> hvVoltage = value
                    "HV_I" -> hvCurrent = value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "PID ${pid.shortName}: exception — ${e.message}")
            }
        }

        // Map standard shortNames to core LiveData fields
        val speedKmh = updatedCustom["SPEED"]
        val speedMph = if (speedKmh != null) ObdParser.kmhToMph(speedKmh) else null
        val power = updatedCustom["PWR"]
            ?: if (hvVoltage != null && hvCurrent != null) hvVoltage * hvCurrent / 1000f else null

        val fuelRateLh = updatedCustom["FUEL_RATE"]
        val fuelRateGph = if (fuelRateLh != null) fuelRateLh / 3.78541f else null  // L/h → GPH

        _liveData.value = _liveData.value.copy(
            soc              = updatedCustom["SOC"]        ?: _liveData.value.soc,
            powerKw          = power                        ?: _liveData.value.powerKw,
            speedMph         = speedMph                     ?: _liveData.value.speedMph,
            rpm              = updatedCustom["RPM"]?.toInt() ?: _liveData.value.rpm,
            hvVoltage        = updatedCustom["HV_V"]       ?: _liveData.value.hvVoltage,
            hvCurrent        = updatedCustom["HV_I"]       ?: _liveData.value.hvCurrent,
            battTempMaxC     = updatedCustom["BATT_T_MAX"] ?: _liveData.value.battTempMaxC,
            battTempMinC     = updatedCustom["BATT_T_MIN"] ?: _liveData.value.battTempMinC,
            engineLoad       = updatedCustom["LOAD"]       ?: _liveData.value.engineLoad,
            fuelRateGph      = fuelRateGph                 ?: _liveData.value.fuelRateGph,
            boostPressurePsi = updatedCustom["BOOST_PSI"] ?: _liveData.value.boostPressurePsi,
            customPids       = updatedCustom
        )

        // Powertrain-aware efficiency computation
        val latestSpeed = _liveData.value.speedMph ?: 0f
        val latestPower = _liveData.value.powerKw  ?: 0f
        when (vehicleProfile.powertrain) {
            PowertrainType.EV, PowertrainType.PHEV -> {
                val raw = ObdParser.calculateEfficiency(latestSpeed, latestPower)
                if (raw > 0f) addEfficiencySample(raw)
                _liveData.value = _liveData.value.copy(instantMiPerKwh = smoothedInstantEfficiency())
            }
            PowertrainType.ICE_GAS, PowertrainType.ICE_DIESEL -> {
                val fuel = _liveData.value.fuelRateGph
                _liveData.value = _liveData.value.copy(
                    instantMpg = if (fuel != null && fuel > 0.01f) latestSpeed / fuel else null
                )
            }
            else -> {}
        }

        // Merge live media metadata (song title/artist from active media session)
        _liveData.value = mediaRemoteManager.injectInto(_liveData.value)

        // Auto-start trip when speed exceeds 3 mph and no active trip is running
        val currentSpeed = _liveData.value.speedMph ?: 0f
        if (currentSpeed > 3f && tripRepository.activeTripId == null) {
            tripRepository.startNewTrip(
                vin = _liveData.value.vin ?: "",
                startSoc = _liveData.value.soc ?: 0f
            )
        }
    }

    private suspend fun pollAndSaveDtcs(vin: String) {
        try {
            queue.execute("ATSH 7DF") // broadcast header for generic Mode 03
            val response = queue.execute("03")
            val codes = ObdParser.parseDtcResponse(response)
            for (code in codes) {
                tripRepository.insertDtc(vin, code)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.d(TAG, "DTC poll failed: ${e.message}") }
    }

    @VisibleForTesting
    internal fun extractRawBytes(response: String, command: String): ByteArray? {
        // Reassemble multi-frame responses: ATCAF1 prefixes each continuation frame with
        // a single-hex-digit line number ("0: ...", "1: ..."). Strip those before parsing.
        val clean = response.lines()
            .filter { it.isNotBlank() && it.trim() != ">" }
            .joinToString("") { line ->
                val t = line.trim()
                if (t.length > 1 && t[1] == ':') t.substring(2).trim() else t
            }
            .replace(" ", "")
            .replace(">", "")   // ATL0 puts the ELM327 prompt on the same line as data
            .trim()
            .uppercase()

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
        } catch (e: Exception) { Log.d(TAG, "extractRawBytes hex parse failed: ${e.message}"); null }
    }

    private fun updateAccumulators() {
        val now = System.currentTimeMillis()
        val dtSeconds = (now - lastStatsUpdateTime) / 1000f
        if (dtSeconds <= 0) return

        val currentPower = _liveData.value.powerKw ?: 0f
        val currentSpeed = _liveData.value.speedMph ?: 0f
        val currentFuelRate = _liveData.value.fuelRateGph ?: 0f

        accumulatedEnergyKwh += (currentPower * dtSeconds) / 3600f
        accumulatedDistanceMiles += (currentSpeed * dtSeconds) / 3600f
        accumulatedFuelGallons += (currentFuelRate * dtSeconds) / 3600f
        lastStatsUpdateTime = now

        val averageUpdates = when (vehicleProfile.powertrain) {
            PowertrainType.EV, PowertrainType.PHEV ->
                if (accumulatedEnergyKwh > 0.01f)
                    _liveData.value.copy(averageMiPerKwh = accumulatedDistanceMiles / accumulatedEnergyKwh)
                else _liveData.value
            PowertrainType.ICE_GAS, PowertrainType.ICE_DIESEL ->
                if (accumulatedFuelGallons > 0.001f)
                    _liveData.value.copy(
                        averageMpg       = accumulatedDistanceMiles / accumulatedFuelGallons,
                        totalFuelGallons = accumulatedFuelGallons
                    )
                else _liveData.value
            else -> _liveData.value
        }

        _liveData.value = averageUpdates.copy(
            tripEnergyKwh     = accumulatedEnergyKwh,
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
        var demoInstant = 3.5f
        var demoAverage = 3.8f
        vehicleProfile = profileManager.getActiveProfile()

        val demoKey = vehicleLayoutManager.resolveKey(null, null, vehicleProfile.id)
        _currentLayoutKey.value = demoKey

        while (coroutineContext.isActive) {
            currentSoc -= 0.01f
            if (currentSoc < 0) currentSoc = 100f
            demoPower = (demoPower + Random.nextFloat() * 10f - 5f).coerceIn(-30f, 80f)
            demoSpeed = (demoSpeed + Random.nextFloat() * 4f - 2f).coerceIn(0f, 80f)
            demoInstant = (demoInstant + Random.nextFloat() * 0.4f - 0.2f).coerceIn(1f, 8f)
            demoAverage = (demoAverage + Random.nextFloat() * 0.02f - 0.01f).coerceIn(2f, 6f)

            _liveData.value = _liveData.value.copy(
                soc               = currentSoc,
                powerKw           = demoPower,
                speedMph          = demoSpeed,
                hvVoltage         = 380f + Random.nextFloat() * 20f,
                hvCurrent         = demoPower * 1000f / 390f,
                instantMiPerKwh   = demoInstant,
                averageMiPerKwh   = demoAverage,
                connectionState   = ConnectionState.Connected,
                vehicleProfile    = vehicleProfile,
                currentSongTitle  = "Demo Mode Active",
                currentSongArtist = "AutoVakt UI Test"
            )
            delay(2000)
        }
    }
}
