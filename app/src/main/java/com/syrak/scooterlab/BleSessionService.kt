package com.syrak.scooterlab.core.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.syrak.scooterlab.R

/**
 * Keeps the process (and therefore the GATT link) alive while a long-running,
 * safety-critical operation is in flight — firmware flashing, the German
 * Maneuver commit, or an active telemetry session with the screen off.
 *
 * It does not own the connection; it simply raises the process priority. The
 * [BleManager] remains the single owner of the GATT client.
 */
class BleSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Session active"
        val body = intent?.getStringExtra(EXTRA_BODY) ?: "Maintaining scooter link"
        val notification = buildNotification(title, body)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_STICKY
    }

    private fun buildNotification(title: String, body: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a scooter session is running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "syrak_session"
        private const val NOTIFICATION_ID = 4201
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"

        fun start(context: Context, title: String, body: String) {
            val intent = Intent(context, BleSessionService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleSessionService::class.java))
        }
    }
}
