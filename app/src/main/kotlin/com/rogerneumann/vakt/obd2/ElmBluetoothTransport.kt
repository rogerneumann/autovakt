package com.rogerneumann.vakt.obd2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Handles the low-level Bluetooth RFCOMM transport for ELM327 communication.
 * Includes auto-reconnect logic and connection state monitoring.
 */
class ElmBluetoothTransport(
    private val bluetoothAdapter: BluetoothAdapter?
) {
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var lastResponseTime = System.currentTimeMillis()
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /**
     * Connects to a specific Bluetooth device.
     */
    suspend fun connect(deviceAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                
                // Cancel discovery as it slows down connection
                bluetoothAdapter.cancelDiscovery()
                
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                
                inputStream = socket?.inputStream
                outputStream = socket?.outputStream
                
                _connectionState.value = ConnectionState.Connected
                lastResponseTime = System.currentTimeMillis()
                startWatchdog()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                cleanup()
            }
        }
    }

    /**
     * Sends a raw command string to the ELM327.
     */
    suspend fun send(command: String) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = (command + "\r").toByteArray()
                outputStream?.write(bytes)
                outputStream?.flush()
            } catch (e: Exception) {
                handleTransportError(e)
            }
        }
    }

    /**
     * Reads the response until the '>' prompt appears.
     */
    suspend fun readResponse(): String {
        return withContext(Dispatchers.IO) {
            val buffer = StringBuilder()
            val tempBuffer = ByteArray(1024)
            
            try {
                while (true) {
                    val bytesRead = inputStream?.read(tempBuffer) ?: -1
                    if (bytesRead == -1) break
                    
                    val chunk = String(tempBuffer, 0, bytesRead)
                    buffer.append(chunk)
                    
                    if (chunk.contains(">")) {
                        lastResponseTime = System.currentTimeMillis() // Reset Watchdog
                        break
                    }
                }
            } catch (e: Exception) {
                handleTransportError(e)
            }
            
            buffer.toString().trim()
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5000)
                val timeSinceLastResponse = System.currentTimeMillis() - lastResponseTime
                if (timeSinceLastResponse > 15000) {
                    handleTransportError(Exception("Watchdog Timeout"))
                    break
                }
            }
        }
    }

    fun disconnect() {
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun handleTransportError(e: Exception) {
        _connectionState.value = ConnectionState.Error("Transport error: ${e.message}")
        cleanup()
    }

    private fun cleanup() {
        watchdogJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }
}
