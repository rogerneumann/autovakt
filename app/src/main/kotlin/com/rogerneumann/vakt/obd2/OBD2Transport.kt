package com.rogerneumann.vakt.obd2

import kotlinx.coroutines.flow.StateFlow

interface OBD2Transport {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(deviceAddress: String)
    suspend fun send(command: String)
    suspend fun readResponse(): String
    fun disconnect()
}
