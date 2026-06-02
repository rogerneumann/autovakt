package com.rogerneumann.autovakt.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.rogerneumann.autovakt.R
import com.rogerneumann.autovakt.abrp.AbrpReporter
import com.rogerneumann.autovakt.data.OBD2Repository
import com.rogerneumann.autovakt.obd2.AutoVaktBridgeServer
import com.rogerneumann.autovakt.obd2.ConnectionState
import com.rogerneumann.autovakt.obd2.ElmBleTransport
import com.rogerneumann.autovakt.obd2.ElmBluetoothTransport
import com.rogerneumann.autovakt.obd2.TransportDelegate
import com.rogerneumann.autovakt.ui.scan.DeviceType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground service that maintains the OBD2 connection while the phone UI
 * is in the background or the user is in Android Auto.
 *
 * A watchdog coroutine detects [ConnectionState.Error] and automatically
 * reconnects with exponential backoff (3 s → 6 s → … → 60 s max).
 * When Android Auto is projecting the backoff floor is capped at 2 s so
 * the gauge display recovers quickly while driving.
 */
@AndroidEntryPoint
class OBD2ForegroundService : android.app.Service() {

    @Inject lateinit var repository: OBD2Repository
    @Inject lateinit var abrpReporter: AbrpReporter
    @Inject lateinit var bridgeServer: AutoVaktBridgeServer
    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var transportDelegate: TransportDelegate
    @Inject lateinit var classicTransport: ElmBluetoothTransport
    @Inject lateinit var bleTransport: ElmBleTransport

    private val channelId = "autovakt_obd_service"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isCarProjectionActive = false
    private var carConnection: CarConnection? = null
    private val projectionObserver = Observer<Int> { type ->
        isCarProjectionActive = type == CarConnection.CONNECTION_TYPE_PROJECTION
    }

    override fun onCreate() {
        super.onCreate()
        // Observe AA projection state on the main thread (CarConnection is LiveData-backed)
        carConnection = CarConnection(this).also { it.type.observeForever(projectionObserver) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoVakt Connected")
            .setContentText("Monitoring vehicle telemetry...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }

        val deviceAddress = sharedPreferences.getString("saved_device_address", null)
        val deviceType    = sharedPreferences.getString("saved_device_type", null)

        if (deviceAddress != null && deviceType != null) {
            serviceScope.launch { attemptConnect(deviceAddress, deviceType) }
            serviceScope.launch { watchdogLoop(deviceAddress, deviceType) }
        } else {
            serviceScope.launch { repository.start(useDemoMode = true) }
        }

        abrpReporter.startReporting(serviceScope, repository.liveData)
        bridgeServer.start()

        return START_STICKY
    }

    /**
     * Connects the transport and starts the OBD2 polling loop.
     * Failures are swallowed; [watchdogLoop] will retry.
     */
    private suspend fun attemptConnect(address: String, type: String) {
        try {
            val transport = if (type == DeviceType.BLE.name) bleTransport else classicTransport
            transportDelegate.setTransport(transport)
            try { transport.disconnect() } catch (_: Exception) {}  // clean BLE state before reconnect
            delay(500L)
            transport.connect(address)
            repository.start(useDemoMode = false)
        } catch (_: Exception) { /* watchdog will retry */ }
    }

    /**
     * Polls the connection state every 2 s. On [ConnectionState.Error],
     * waits a back-off period (shorter when AA is projecting) then reconnects.
     * Cancelled automatically when the service is destroyed (explicit disconnect).
     */
    private suspend fun watchdogLoop(address: String, type: String) {
        var backoffMs = 3_000L
        while (true) {
            delay(2_000L)  // cancellable — throws CancellationException on service destroy
            val state = repository.liveData.value.connectionState
            if (state !is ConnectionState.Error) continue

            // Shorter backoff when the user is actively looking at AA
            val waitMs = if (isCarProjectionActive) 2_000L else backoffMs
            delay(waitMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)

            attemptConnect(address, type)

            // Reset backoff if reconnection succeeds within 20 s
            val reconnected = withTimeoutOrNull(20_000L) {
                repository.liveData.first { it.connectionState is ConnectionState.Connected }
            }
            if (reconnected != null) backoffMs = 3_000L
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "OBD2 Connection Status",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        carConnection?.type?.removeObserver(projectionObserver)
        serviceScope.cancel()   // cancels watchdogLoop — no reconnect after explicit stop
        repository.stop()
        bridgeServer.stop()
    }
}
