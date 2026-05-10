package com.rogerneumann.vakt.obd2

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime-switchable transport wrapper. Allows swapping between Classic (RFCOMM)
 * and BLE transports without restarting the command queue.
 */
@Singleton
class TransportDelegate @Inject constructor(
    private val classicTransport: ElmBluetoothTransport
) : OBD2Transport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeTransport: OBD2Transport = classicTransport
    private var stateCollectionJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    init {
        mirrorState(classicTransport)
    }

    fun setTransport(transport: OBD2Transport) {
        activeTransport = transport
        mirrorState(transport)
    }

    fun getActiveTransport(): OBD2Transport = activeTransport

    private fun mirrorState(transport: OBD2Transport) {
        stateCollectionJob?.cancel()
        stateCollectionJob = scope.launch {
            transport.connectionState.collect { _connectionState.value = it }
        }
    }

    override suspend fun connect(deviceAddress: String) = activeTransport.connect(deviceAddress)
    override suspend fun send(command: String) = activeTransport.send(command)
    override suspend fun readResponse(): String = activeTransport.readResponse()
    override fun disconnect() {
        activeTransport.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }
}
