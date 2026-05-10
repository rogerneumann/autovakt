package com.rogerneumann.vakt.obd2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the low-level Bluetooth RFCOMM transport for ELM327 communication.
 */
@Singleton
class ElmBluetoothTransport @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter?
) : OBD2Transport {
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // P1 FIX: Store address so the watchdog can reconnect automatically.
    private var lastDeviceAddress: String? = null
    private var lastResponseTime = System.currentTimeMillis()
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override suspend fun connect(deviceAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting
                lastDeviceAddress = deviceAddress   // P1: remember for watchdog reconnect
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
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

    override suspend fun send(command: String) {
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

    override suspend fun readResponse(): String {
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
                        lastResponseTime = System.currentTimeMillis()
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
                    // P1 FIX: attempt reconnect instead of just erroring out.
                    val addr = lastDeviceAddress
                    if (addr != null) {
                        _connectionState.value = ConnectionState.Connecting
                        cleanup() // close dead socket first
                        try {
                            // connect() will call startWatchdog() on success,
                            // which cancels this job via watchdogJob?.cancel().
                            connect(addr)
                        } catch (e: Exception) {
                            _connectionState.value = ConnectionState.Error("Reconnect failed: ${e.message}")
                            // Loop continues — will retry on next 5s tick.
                        }
                    } else {
                        _connectionState.value = ConnectionState.Error("Watchdog timeout — no address to reconnect")
                        break
                    }
                }
            }
        }
    }

    override fun disconnect() {
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
            // Ignore
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }
}
