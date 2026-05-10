package com.rogerneumann.vakt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rogerneumann.vakt.data.OBD2Repository
import com.rogerneumann.vakt.obd2.TransportDelegate
import com.rogerneumann.vakt.ui.scan.DeviceType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A sticky foreground service that maintains the OBD2 connection
 * even when the phone UI is closed or the user is in another AA app.
 */
@AndroidEntryPoint
class OBD2ForegroundService : Service() {

    @Inject lateinit var repository: OBD2Repository
    @Inject lateinit var bridgeServer: com.rogerneumann.vakt.obd2.VaktBridgeServer
    @Inject lateinit var sharedPreferences: SharedPreferences
    @Inject lateinit var transportDelegate: TransportDelegate
    @Inject lateinit var classicTransport: com.rogerneumann.vakt.obd2.ElmBluetoothTransport
    @Inject lateinit var bleTransport: com.rogerneumann.vakt.obd2.ElmBleTransport
    private val channelId = "vakt_obd_service"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vakt Connected")
            .setContentText("Monitoring vehicle telemetry...")
            .setSmallIcon(com.rogerneumann.vakt.R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }

        // Setup transport based on saved device preferences
        val deviceAddress = sharedPreferences.getString("saved_device_address", null)
        val deviceType = sharedPreferences.getString("saved_device_type", null)

        if (deviceAddress != null && deviceType != null) {
            // Connect to the saved device
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Select appropriate transport based on device type
                    val transport = if (deviceType == DeviceType.BLE.name) {
                        bleTransport
                    } else {
                        classicTransport
                    }
                    transportDelegate.setTransport(transport)

                    // Connect to the device
                    transport.connect(deviceAddress)

                    // Start the polling loop with real data
                    repository.start(useDemoMode = false)
                } catch (e: Exception) {
                    // Fallback to demo mode if connection fails
                    repository.start(useDemoMode = true)
                }
            }
        } else {
            // No device saved - start with demo mode
            repository.start(useDemoMode = true)
        }

        // Start the TCP bridge for 3rd party apps
        bridgeServer.start()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "OBD2 Connection Status",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        repository.stop()
        bridgeServer.stop()
    }
}
