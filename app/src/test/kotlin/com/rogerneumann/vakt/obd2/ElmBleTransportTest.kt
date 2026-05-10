package com.rogerneumann.vakt.obd2

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElmBleTransportTest {

    private lateinit var transport: ElmBleTransport
    private lateinit var mockAdapter: BluetoothAdapter
    private lateinit var mockDevice: BluetoothDevice
    private lateinit var mockGatt: BluetoothGatt
    private lateinit var mockService: BluetoothGattService
    private lateinit var mockTxChar: BluetoothGattCharacteristic
    private lateinit var mockRxChar: BluetoothGattCharacteristic
    private lateinit var capturedCallback: BluetoothGattCallback

    @Before
    fun setup() {
        mockAdapter = mockk(relaxed = true) {
            every { isEnabled } returns true
        }
        mockDevice = mockk(relaxed = true)
        mockGatt = mockk(relaxed = true)
        mockService = mockk(relaxed = true)
        mockTxChar = mockk(relaxed = true)
        mockRxChar = mockk(relaxed = true)

        val callbackSlot = slot<BluetoothGattCallback>()
        every { mockDevice.connectGatt(any(), any(), capture(callbackSlot), any()) } answers {
            capturedCallback = callbackSlot.captured
            mockGatt
        }
        every { mockDevice.connectGatt(any(), any(), capture(callbackSlot)) } answers {
            capturedCallback = callbackSlot.captured
            mockGatt
        }

        every { mockGatt.getService(any<UUID>()) } returns null
        every { mockGatt.discoverServices() } returns true
        every { mockGatt.requestMtu(any()) } returns true
        every { mockAdapter.getRemoteDevice(any()) } returns mockDevice

        transport = ElmBleTransport(mockAdapter)
    }

    @Test
    fun testConnectInitiatesGattConnection() = runTest {
        val deviceAddress = "AA:BB:CC:DD:EE:FF"

        transport.connect(deviceAddress)
        delay(500) // Let the coroutine settle

        verify { mockAdapter.getRemoteDevice(deviceAddress) }
        verify { mockAdapter.cancelDiscovery() }
    }

    @Test
    fun testConnectionStateTransition() = runTest {
        val deviceAddress = "AA:BB:CC:DD:EE:FF"

        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)

        val stateJob = launch {
            transport.connectionState.collect { state ->
                // Collect states as they change
            }
        }

        transport.connect(deviceAddress)
        delay(100)

        // Simulate GATT connection callback
        capturedCallback.onConnectionStateChange(mockGatt, 0, BluetoothGatt.STATE_CONNECTED)

        delay(200)
        assertTrue(transport.connectionState.value is ConnectionState.Connected)

        stateJob.cancel()
    }

    @Test
    fun testSendCommandWithChunking() = runTest {
        // Setup mocks for send
        every { mockGatt.setNotificationEnabled(any(), any()) } returns true
        every { mockTxChar.value = any() } returns Unit

        val deviceAddress = "AA:BB:CC:DD:EE:FF"
        transport.connect(deviceAddress)

        // Simulate successful connection and service discovery
        capturedCallback.onConnectionStateChange(mockGatt, 0, BluetoothGatt.STATE_CONNECTED)
        delay(100)

        // Mock service and characteristics
        every { mockGatt.getService(any<UUID>()) } returns mockService
        every { mockService.getCharacteristic(any()) } answers {
            when (it.invocation.args[0]) {
                // Return TX or RX char based on UUID
                else -> mockTxChar
            }
        }

        // Simulate services discovered
        capturedCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)
        delay(100)

        transport.send("ATE0")
        delay(100)

        // Verify write was called (may be chunked)
        verify(atLeast = 1) { mockGatt.writeCharacteristic(any(), any(), any()) }
    }

    @Test
    fun testReadResponseWaitForDelimiter() = runTest {
        val deviceAddress = "AA:BB:CC:DD:EE:FF"

        transport.connect(deviceAddress)
        delay(100)

        capturedCallback.onConnectionStateChange(mockGatt, 0, BluetoothGatt.STATE_CONNECTED)
        delay(100)

        // Simulate receiving data in chunks, then delimiter
        val readJob = launch {
            delay(200)
            val chunk1 = "OK\r\n".toByteArray()
            capturedCallback.onCharacteristicChanged(mockGatt, mockRxChar, chunk1)

            delay(50)
            val chunk2 = ">".toByteArray()
            capturedCallback.onCharacteristicChanged(mockGatt, mockRxChar, chunk2)
        }

        val response = transport.readResponse()
        readJob.join()

        assertTrue(response.contains(">"))
        assertTrue(response.contains("OK"))
    }

    @Test
    fun testDisconnectClosesResources() = runTest {
        val deviceAddress = "AA:BB:CC:DD:EE:FF"

        transport.connect(deviceAddress)
        delay(100)

        capturedCallback.onConnectionStateChange(mockGatt, 0, BluetoothGatt.STATE_CONNECTED)
        delay(100)

        transport.disconnect()
        delay(100)

        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
        verify { mockGatt.close() }
    }

    @Test
    fun testMtuNegotiation() = runTest {
        val deviceAddress = "AA:BB:CC:DD:EE:FF"

        transport.connect(deviceAddress)
        delay(100)

        capturedCallback.onConnectionStateChange(mockGatt, 0, BluetoothGatt.STATE_CONNECTED)
        delay(100)

        capturedCallback.onServicesDiscovered(mockGatt, BluetoothGatt.GATT_SUCCESS)
        delay(100)

        verify { mockGatt.requestMtu(512) }

        capturedCallback.onMtuChanged(mockGatt, 512, BluetoothGatt.GATT_SUCCESS)
        delay(100)

        // MTU negotiation should complete without error
        assertEquals(ConnectionState.Connected, transport.connectionState.value)
    }

    @Test
    fun testBluetoothDisabledError() = runTest {
        val disabledAdapter = mockk<BluetoothAdapter> {
            every { isEnabled } returns false
        }

        val transportWithDisabled = ElmBleTransport(disabledAdapter)
        transportWithDisabled.connect("AA:BB:CC:DD:EE:FF")
        delay(100)

        assertTrue(transport.connectionState.value is ConnectionState.Error)
    }
}
