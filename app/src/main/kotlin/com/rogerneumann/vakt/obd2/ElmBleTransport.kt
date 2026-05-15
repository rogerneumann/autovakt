package com.rogerneumann.vakt.obd2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.annotation.SuppressLint
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles BLE GATT communication with ELM327-compatible adapters.
 * Supports NUS (Nordic UART Service) and other standard BLE profiles.
 */
@SuppressLint("MissingPermission")
@Singleton
class ElmBleTransport @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter?
) : OBD2Transport {

    // Service and characteristic UUIDs (in order of preference)
    private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private val FALLBACK_SERVICE_UUIDS = listOf(
        UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000ABF0-0000-1000-8000-00805F9B34FB")
    )

    private val FALLBACK_TX_UUIDS = listOf(
        UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000ABF1-0000-1000-8000-00805F9B34FB")
    )

    private val FALLBACK_RX_UUIDS = listOf(
        UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000ABF2-0000-1000-8000-00805F9B34FB")
    )

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    private val DEFAULT_MTU = 20
    private val PREFERRED_MTU = 512

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var currentMtu = DEFAULT_MTU

    private var lastDeviceAddress: String? = null
    private var lastResponseTime = System.currentTimeMillis()
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Channel for receiving notification data — recreated on each connect() to avoid closed-channel sends after cleanup()
    private var rxChannel = Channel<ByteArray>(capacity = 100)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connected
                    lastResponseTime = System.currentTimeMillis()
                    startWatchdog()
                    // Discover services
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattError("Service discovery failed", null)
                return
            }

            if (!setupCharacteristics(gatt)) {
                handleGattError("Could not find TX/RX characteristics", null)
                return
            }

            // Request MTU negotiation
            gatt.requestMtu(PREFERRED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
            } else {
                currentMtu = DEFAULT_MTU
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattError("Write characteristic failed", null)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value
            if (data != null) {
                scope.launch {
                    lastResponseTime = System.currentTimeMillis()
                    rxChannel.send(data)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            scope.launch {
                lastResponseTime = System.currentTimeMillis()
                rxChannel.send(value)
            }
        }
    }

    override suspend fun connect(deviceAddress: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting
                lastDeviceAddress = deviceAddress
                rxChannel = Channel(capacity = 100) // fresh channel — prior one may be closed from cleanup()

                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                bluetoothAdapter.cancelDiscovery()

                // Connect to GATT
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(null, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(null, false, gattCallback)
                }

                // Wait briefly for connection to establish
                delay(1000)
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                cleanup()
            }
        }
    }

    /**
     * Attempts to locate and enable TX/RX characteristics.
     * Tries NUS first, then fallback UUIDs.
     */
    private fun setupCharacteristics(gatt: BluetoothGatt): Boolean {
        // Try NUS service first
        val nusService = gatt.getService(NUS_SERVICE_UUID)
        if (nusService != null) {
            txCharacteristic = nusService.getCharacteristic(NUS_TX_UUID)
            rxCharacteristic = nusService.getCharacteristic(NUS_RX_UUID)
            if (txCharacteristic != null && rxCharacteristic != null) {
                enableNotifications(gatt, rxCharacteristic!!)
                return true
            }
        }

        // Try fallback services
        for (i in FALLBACK_SERVICE_UUIDS.indices) {
            val service = gatt.getService(FALLBACK_SERVICE_UUIDS[i])
            if (service != null) {
                txCharacteristic = service.getCharacteristic(FALLBACK_TX_UUIDS[i])
                rxCharacteristic = service.getCharacteristic(FALLBACK_RX_UUIDS[i])
                if (txCharacteristic != null && rxCharacteristic != null) {
                    enableNotifications(gatt, rxCharacteristic!!)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Enables notifications on the RX characteristic.
     */
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Modern API (API 33+): use new writeDescriptor signature
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        } else {
            // Legacy API (pre-API 33)
            @Suppress("DEPRECATION")
            characteristic.descriptors.forEach { descriptor ->
                if (descriptor.uuid == CCCD_UUID) {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    override suspend fun send(command: String) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = (command + "\r").toByteArray()
                val tx = txCharacteristic ?: return@withContext

                // Chunk into MTU-safe writes
                val chunkSize = (currentMtu - 3).coerceAtLeast(1) // Account for ATT header
                for (i in bytes.indices step chunkSize) {
                    val end = (i + chunkSize).coerceAtMost(bytes.size)
                    val chunk = bytes.sliceArray(i until end)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt?.writeCharacteristic(
                            tx,
                            chunk,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        tx.value = chunk
                        @Suppress("DEPRECATION")
                        gatt?.writeCharacteristic(tx)
                    }

                    delay(50) // Brief delay between chunks
                }
            } catch (e: Exception) {
                handleGattError("Send failed", e)
            }
        }
    }

    override suspend fun readResponse(): String {
        return withContext(Dispatchers.IO) {
            val buffer = StringBuilder()
            val timeout = 5000L // 5 second timeout
            val startTime = System.currentTimeMillis()

            try {
                while (System.currentTimeMillis() - startTime < timeout) {
                    val chunk = rxChannel.tryReceive().getOrNull()
                    if (chunk != null) {
                        val text = String(chunk)
                        buffer.append(text)

                        if (text.contains(">")) {
                            lastResponseTime = System.currentTimeMillis()
                            break
                        }
                    } else {
                        delay(10) // Brief sleep to avoid busy-waiting
                    }
                }
            } catch (e: Exception) {
                handleGattError("Read failed", e)
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
                    val addr = lastDeviceAddress
                    if (addr != null) {
                        _connectionState.value = ConnectionState.Connecting
                        cleanup()
                        try {
                            connect(addr)
                        } catch (e: Exception) {
                            _connectionState.value = ConnectionState.Error("Reconnect failed: ${e.message}")
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

    private fun handleGattError(message: String, e: Exception?) {
        _connectionState.value = ConnectionState.Error(
            if (e != null) "$message: ${e.message}" else message
        )
        cleanup()
    }

    private fun cleanup() {
        watchdogJob?.cancel()
        try {
            gatt?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            gatt = null
            txCharacteristic = null
            rxCharacteristic = null
            currentMtu = DEFAULT_MTU
            rxChannel.close()
        }
    }
}
