package com.rogerneumann.autovakt.obd2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles BLE GATT communication with ELM327-compatible adapters.
 * Supports NUS (Nordic UART Service) and other standard BLE profiles.
 *
 * Connection sequence (strictly serialized — one GATT op at a time):
 *   connectGatt() → onConnectionStateChange(CONNECTED) → discoverServices()
 *   → onServicesDiscovered() → setupCharacteristics() → writeDescriptor(CCCD)
 *   → onDescriptorWrite() → requestMtu()
 *   → onMtuChanged() → [Connected state set here, setupDeferred completed]
 *
 * If the RX characteristic has no CCCD descriptor, requestMtu() fires directly
 * from onServicesDiscovered(). connect() blocks on setupDeferred so the caller
 * never sees Connected until TX/RX characteristics are actually ready to use.
 */
@SuppressLint("MissingPermission")
@Singleton
class ElmBleTransport @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter?
) : OBD2Transport {

    private val TAG = "AutoVaktBLE"

    // NUS (Nordic UART Service) — most ELM327 BLE clones
    private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_TX_UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_RX_UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    // Fallback profiles (HM-10 / JDY-08 / generic OBD clones)
    private val FALLBACK_SERVICE_UUIDS = listOf(
        UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000ABF0-0000-1000-8000-00805F9B34FB")
    )
    private val FALLBACK_TX_UUIDS = listOf(
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000ABF1-0000-1000-8000-00805F9B34FB")
    )
    private val FALLBACK_RX_UUIDS = listOf(
        UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000ABF2-0000-1000-8000-00805F9B34FB")
    )

    private val CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    private val DEFAULT_MTU   = 20
    private val PREFERRED_MTU = 512

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var currentMtu = DEFAULT_MTU

    private var lastDeviceAddress: String? = null
    private var lastResponseTime  = System.currentTimeMillis()
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // True while a writeDescriptor(CCCD) is in-flight. requestMtu() must not fire
    // until onDescriptorWrite() clears this — Android BLE allows only one GATT op at a time.
    private var descriptorWritePending = false

    // Completed by onMtuChanged (or on error) so connect() knows setup is done.
    private var setupDeferred: CompletableDeferred<Unit>? = null

    // Recreated on each connect() to avoid sending to a closed channel after cleanup().
    private var rxChannel = Channel<ByteArray>(capacity = 100)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected — discovering services")
                    // Do NOT set Connected here; TX/RX chars are not set up yet.
                    // Remain in Connecting until onMtuChanged completes setup.
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    val deferred = setupDeferred
                    if (deferred != null && deferred.isActive) {
                        deferred.completeExceptionally(Exception("GATT disconnected during setup (status=$status)"))
                    }
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed (status=$status)")
                failSetup("Service discovery failed (status=$status)")
                return
            }

            Log.d(TAG, "Services discovered: ${gatt.services.map { it.uuid }}")

            if (!setupCharacteristics(gatt)) {
                Log.e(TAG, "No usable TX/RX characteristics found")
                failSetup("Could not find TX/RX characteristics")
                return
            }

            // If enableNotifications() issued a writeDescriptor(), wait for onDescriptorWrite
            // before requesting MTU — Android BLE allows only one outstanding GATT op at a time.
            if (!descriptorWritePending) {
                Log.i(TAG, "No CCCD write needed — requesting MTU $PREFERRED_MTU")
                gatt.requestMtu(PREFERRED_MTU)
            }
            // else: onDescriptorWrite will call requestMtu()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWritePending = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed (status=$status) — proceeding to MTU anyway")
            } else {
                Log.d(TAG, "CCCD written — requesting MTU $PREFERRED_MTU")
            }
            gatt.requestMtu(PREFERRED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU negotiated to $mtu")
                mtu
            } else {
                Log.w(TAG, "MTU negotiation failed (status=$status), using $DEFAULT_MTU")
                DEFAULT_MTU
            }
            // Full setup complete — ready for commands.
            _connectionState.value = ConnectionState.Connected
            lastResponseTime = System.currentTimeMillis()
            startWatchdog()
            setupDeferred?.complete(Unit)
            Log.i(TAG, "BLE transport ready (MTU=$currentMtu)")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed (status=$status)")
            }
        }

        // API < 33
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            scope.launch {
                lastResponseTime = System.currentTimeMillis()
                rxChannel.send(data)
            }
        }

        // API 33+
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
            Log.e(TAG, "connect() aborted — Bluetooth disabled")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting
                lastDeviceAddress = deviceAddress
                rxChannel    = Channel(capacity = 100)
                setupDeferred = CompletableDeferred()

                Log.i(TAG, "Connecting to $deviceAddress")
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                bluetoothAdapter.cancelDiscovery()

                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(null, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(null, false, gattCallback)
                }

                // Block until full setup (GATT connect → services → chars → MTU) or timeout.
                val ok = withTimeoutOrNull(15_000L) {
                    setupDeferred?.await()
                    true
                }

                if (ok == null) {
                    Log.e(TAG, "connect() timed out waiting for GATT setup")
                    _connectionState.value = ConnectionState.Error("BLE setup timed out")
                    cleanup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "connect() failed: ${e.message}")
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                cleanup()
            }
        }
    }

    private fun setupCharacteristics(gatt: BluetoothGatt): Boolean {
        // Try NUS first
        val nusService = gatt.getService(NUS_SERVICE_UUID)
        if (nusService != null) {
            txCharacteristic = nusService.getCharacteristic(NUS_TX_UUID)
            rxCharacteristic = nusService.getCharacteristic(NUS_RX_UUID)
            if (txCharacteristic != null && rxCharacteristic != null) {
                Log.d(TAG, "Using NUS service")
                enableNotifications(gatt, rxCharacteristic!!)
                return true
            }
        }

        // Fallback profiles — detect TX/RX by characteristic properties rather than fixed UUID
        // order. OBDLink CX (FFF0): FFF1=NOTIFY, FFF2=WRITE_NO_RESPONSE — the opposite of what
        // many generic clones use, so hardcoding UUID→direction is unreliable across adapters.
        for (serviceUuid in FALLBACK_SERVICE_UUIDS) {
            val service = gatt.getService(serviceUuid) ?: continue
            val chars = service.characteristics
            val tx = chars.firstOrNull { c ->
                c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            }
            val rx = chars.firstOrNull { c ->
                c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }
            if (tx != null && rx != null) {
                txCharacteristic = tx
                rxCharacteristic = rx
                Log.d(TAG, "Using fallback service $serviceUuid  TX=${tx.uuid}  RX=${rx.uuid}")
                enableNotifications(gatt, rx)
                return true
            }
        }

        return false
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptorWritePending = true
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.descriptors.forEach { descriptor ->
                if (descriptor.uuid == CCCD_UUID) {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    descriptorWritePending = true
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
    }

    override suspend fun send(command: String) {
        withContext(Dispatchers.IO) {
            try {
                val tx = txCharacteristic
                if (tx == null) {
                    Log.w(TAG, "send() called but txCharacteristic is null — dropping: $command")
                    return@withContext
                }

                // Use WRITE_NO_RESPONSE if the characteristic doesn't support acknowledged writes
                // (OBDLink CX FFF1 and most UART-over-BLE TX chars use WRITE_NO_RESPONSE)
                val writeType = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                Log.d(TAG, "TX (writeType=$writeType): $command")
                val bytes     = (command + "\r").toByteArray()
                val chunkSize = (currentMtu - 3).coerceAtLeast(1)

                for (i in bytes.indices step chunkSize) {
                    val chunk = bytes.sliceArray(i until (i + chunkSize).coerceAtMost(bytes.size))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt?.writeCharacteristic(tx, chunk, writeType)
                    } else {
                        @Suppress("DEPRECATION")
                        tx.value = chunk
                        @Suppress("DEPRECATION")
                        tx.writeType = writeType
                        @Suppress("DEPRECATION")
                        gatt?.writeCharacteristic(tx)
                    }

                    if (bytes.size > chunkSize) delay(50)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // let coroutine cancellation propagate; don't tear down GATT
            } catch (e: Exception) {
                Log.e(TAG, "send() error: ${e.message}")
                handleGattError("Send failed", e)
            }
        }
    }

    override suspend fun readResponse(): String {
        return withContext(Dispatchers.IO) {
            val buffer    = StringBuilder()
            val startTime = System.currentTimeMillis()

            try {
                while (System.currentTimeMillis() - startTime < 5_000L) {
                    val chunk = rxChannel.tryReceive().getOrNull()
                    if (chunk != null) {
                        val text = String(chunk)
                        buffer.append(text)
                        if (text.contains(">")) {
                            lastResponseTime = System.currentTimeMillis()
                            break
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // command timeout from ElmCommandQueue — don't tear down GATT
            } catch (e: Exception) {
                Log.e(TAG, "readResponse() error: ${e.message}")
                handleGattError("Read failed", e)
            }

            val response = buffer.toString().trim()
            if (response.isNotEmpty()) Log.d(TAG, "RX: $response")
            response
        }
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
                        try { connect(addr) } catch (e: Exception) {
                            Log.e(TAG, "Watchdog reconnect failed: ${e.message}")
                            _connectionState.value = ConnectionState.Error("Reconnect failed: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Watchdog: no address to reconnect")
                        _connectionState.value = ConnectionState.Error("Watchdog timeout — no address")
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

    private fun failSetup(reason: String) {
        _connectionState.value = ConnectionState.Error(reason)
        setupDeferred?.takeIf { it.isActive }?.completeExceptionally(Exception(reason))
        cleanup()
    }

    private fun handleGattError(message: String, e: Exception?) {
        val full = if (e != null) "$message: ${e.message}" else message
        Log.e(TAG, full)
        _connectionState.value = ConnectionState.Error(full)
        cleanup()
    }

    private fun cleanup() {
        watchdogJob?.cancel()
        descriptorWritePending = false
        try { gatt?.close() } catch (_: Exception) {}
        finally {
            gatt             = null
            txCharacteristic = null
            rxCharacteristic = null
            currentMtu       = DEFAULT_MTU
            rxChannel.close()
        }
    }
}
