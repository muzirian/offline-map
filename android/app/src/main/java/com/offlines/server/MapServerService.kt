package com.offlines.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.offlines.MainActivity
import java.io.File

class MapServerService : Service() {

    private var server: MapServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server?.isRunning == true) return START_STICKY

        val port = intent?.getIntExtra("port", 8080) ?: 8080
        val storagePath = intent?.getStringExtra("storagePath")
            ?: filesDir.absolutePath

        server = MapServer(File(storagePath), port)
        server?.start()

        if (server?.isRunning != true) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Map Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the map server is running"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offlines Server")
            .setContentText("Serving maps on port ${server?.portNumber ?: "..."}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "map_server_channel"
        const val NOTIFICATION_ID = 1001
    }
}
