package com.rogerneumann.vakt.ui.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val type: DeviceType
)

enum class DeviceType {
    CLASSIC, BLE
}

data class ScanState(
    val isScanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class DeviceScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) : ViewModel() {

    private val deviceMap = mutableMapOf<String, ScannedDevice>()
    private var receiverRegistered = false

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    // Broadcast receiver for Classic Bluetooth discovery
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    device?.let {
                        val address = it.address
                        val name = it.name ?: "Unknown"

                        deviceMap[address] = ScannedDevice(
                            address = address,
                            name = name,
                            rssi = rssi,
                            type = DeviceType.CLASSIC
                        )
                        updateDeviceList()
                    }
                }
            }
        }
    }

    // Scan callback for BLE
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val address = it.device.address
                val name = it.device.name ?: "Unknown"
                val rssi = it.rssi

                deviceMap[address] = ScannedDevice(
                    address = address,
                    name = name,
                    rssi = rssi,
                    type = DeviceType.BLE
                )
                updateDeviceList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanState.value = _scanState.value.copy(
                error = "BLE scan failed with error code: $errorCode"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBluetoothPermissions()) {
            _scanState.value = _scanState.value.copy(
                error = "Missing Bluetooth permissions"
            )
            return
        }

        _scanState.value = ScanState(isScanning = true)
        deviceMap.clear()

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Start Classic Bluetooth discovery
                startClassicDiscovery()

                // Start BLE scan
                startBleScan()
            } catch (e: Exception) {
                _scanState.value = _scanState.value.copy(
                    error = "Scan failed: ${e.message}",
                    isScanning = false
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bluetoothAdapter?.cancelDiscovery()
            if (receiverRegistered) {
                context.unregisterReceiver(discoveryReceiver)
                receiverRegistered = false
            }
            bleScanner?.stopScan(bleScanCallback)
            _scanState.value = _scanState.value.copy(isScanning = false)
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        if (!hasBluetoothPermissions()) return

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        try {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            bluetoothAdapter?.startDiscovery()
        } catch (e: Exception) {
            _scanState.value = _scanState.value.copy(error = "Classic scan failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasBluetoothPermissions()) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner?.startScan(emptyList(), settings, bleScanCallback)
        } catch (e: Exception) {
            _scanState.value = _scanState.value.copy(error = "BLE scan failed: ${e.message}")
        }
    }

    private fun updateDeviceList() {
        _scanState.value = _scanState.value.copy(
            devices = deviceMap.values.sortedByDescending { it.rssi }.take(10)
        )
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: BLUETOOTH_SCAN (neverForLocation) + BLUETOOTH_CONNECT; no location needed
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            // API 26–30: BLE scanning requires ACCESS_FINE_LOCATION
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
