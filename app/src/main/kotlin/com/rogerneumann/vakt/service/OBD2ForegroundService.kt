package com.rogerneumann.vakt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rogerneumann.vakt.data.OBD2Repository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A sticky foreground service that maintains the OBD2 connection 
 * even when the phone UI is closed or the user is in another AA app.
 */
@AndroidEntryPoint
class OBD2ForegroundService : Service() {

    @Inject lateinit var repository: OBD2Repository
    private val channelId = "vakt_obd_service"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vakt Connected")
            .setContentText("Monitoring vehicle telemetry...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
            
        startForeground(1, notification)
        
        // Start the polling loop (Demo mode enabled for now)
        repository.start(useDemoMode = true)
        
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
    }
}
