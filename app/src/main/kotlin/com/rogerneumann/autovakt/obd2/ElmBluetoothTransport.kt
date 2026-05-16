package com.rogerneumann.autovakt.obd2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the low-level Bluetooth Classic RFCOMM transport for ELM327 communication.
 *
 * Connection strategy: ELM327 clones do not register SDP service records, so
 * createRfcommSocketToServiceRecord() typically hangs or connects to the wrong
 * channel. We use a reflection call to createRfcommSocket(1) (channel 1 is the
 * ELM327 standard) with a 12-second timeout, falling back to the insecure SPP
 * UUID path only if reflection fails.
 */
@SuppressLint("MissingPermission")
@Singleton
class ElmBluetoothTransport @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter?
) : OBD2Transport {

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val TAG = "AutoVaktBT"

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var lastDeviceAddress: String? = null
    private var lastResponseTime = System.currentTimeMillis()
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override suspend fun connect(deviceAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            Log.e(TAG, "connect() aborted — Bluetooth disabled")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting
                lastDeviceAddress = deviceAddress
                Log.i(TAG, "Connecting to $deviceAddress")

                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                bluetoothAdapter.cancelDiscovery()

                // Strategy (matches pires/android-obd-reader consensus):
                //   1. Public SDP path — works when the adapter registers a service record
                //      and lets the OS find the actual RFCOMM channel (may not be 1).
                //   2. Reflection fallback — createRfcommSocket(1) bypasses SDP entirely;
                //      used by virtually all ELM327 clones which advertise on channel 1
                //      but may not respond to SDP queries reliably on all BT stacks.
                // Both attempts are wrapped in a 12 s timeout (superior to the Java reference
                // implementations, which block indefinitely).
                val newSocket = connectWithFallback(device)
                socket = newSocket

                // 500 ms settle — cheap adapters need time after connect() before they
                // will ACK the first ELM327 ATZ command (observed in AndrOBD issue #233).
                delay(500L)

                inputStream  = newSocket.inputStream
                outputStream = newSocket.outputStream
                _connectionState.value = ConnectionState.Connected
                lastResponseTime = System.currentTimeMillis()
                Log.i(TAG, "Connected to $deviceAddress")
                startWatchdog()

            } catch (e: Exception) {
                Log.e(TAG, "connect() failed: ${e.javaClass.simpleName}: ${e.message}")
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
                withTimeoutOrNull(5_000L) {
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
                }
            } catch (e: Exception) {
                handleTransportError(e)
            }

            buffer.toString().trim()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectWithFallback(device: android.bluetooth.BluetoothDevice): BluetoothSocket {
        // Primary: public SDP path
        val primary = device.createRfcommSocketToServiceRecord(SPP_UUID)
        Log.d(TAG, "Trying SDP path (SPP UUID)")
        val primaryOk = withTimeoutOrNull(12_000L) {
            runCatching { primary.connect() }.isSuccess
        } ?: false

        if (primaryOk) {
            Log.d(TAG, "SDP path connected")
            return primary
        }

        // Primary failed — close it before opening a new socket
        runCatching { primary.close() }
        Log.w(TAG, "SDP path failed, trying reflection (channel 1)")

        // Fallback: reflection createRfcommSocket(1) — bypasses SDP lookup
        val fallback = try {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            m.invoke(device, 1) as BluetoothSocket
        } catch (e: Exception) {
            throw IOException("Both socket paths failed (reflection unavailable: ${e.message})")
        }

        val fallbackOk = withTimeoutOrNull(12_000L) {
            runCatching { fallback.connect() }.isSuccess
        } ?: false

        if (!fallbackOk) {
            runCatching { fallback.close() }
            throw IOException("connect() timed out on both SDP and reflection paths")
        }

        Log.d(TAG, "Reflection path (channel 1) connected")
        return fallback
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                val elapsed = System.currentTimeMillis() - lastResponseTime
                if (elapsed > 15_000L) {
                    val addr = lastDeviceAddress
                    if (addr != null) {
                        Log.w(TAG, "Watchdog: no response for ${elapsed}ms — reconnecting to $addr")
                        _connectionState.value = ConnectionState.Connecting
                        cleanup()
                        try {
                            connect(addr)
                        } catch (e: Exception) {
                            Log.e(TAG, "Watchdog reconnect failed: ${e.message}")
                            _connectionState.value = ConnectionState.Error("Reconnect failed: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Watchdog: no address to reconnect")
                        _connectionState.value = ConnectionState.Error("Watchdog timeout — no address to reconnect")
                        break
                    }
                }
            }
        }
    }

    override fun disconnect() {
        Log.i(TAG, "disconnect() called for $lastDeviceAddress")
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun handleTransportError(e: Exception) {
        Log.e(TAG, "Transport error: ${e.message}")
        _connectionState.value = ConnectionState.Error("Transport error: ${e.message}")
        cleanup()
    }

    private fun cleanup() {
        watchdogJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }
}
