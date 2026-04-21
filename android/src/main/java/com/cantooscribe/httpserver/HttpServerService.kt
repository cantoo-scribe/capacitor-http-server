package com.cantooscribe.httpserver

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

/**
 * Foreground service whose only purpose is to keep the hosting process alive
 * while the embedded HTTP server is running. The server itself is owned by
 * the plugin instance; the service just holds a `startForeground` notification.
 */
class HttpServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "HTTP server running"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: DEFAULT_CHANNEL_NAME
        val iconResName = intent?.getStringExtra(EXTRA_ICON)

        ensureChannel(channelId, channelName)

        val iconRes = iconResName
            ?.let { resources.getIdentifier(it, "drawable", packageName) }
            ?.takeIf { it != 0 }
            ?: android.R.drawable.stat_sys_download_done

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun ensureChannel(id: String, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val NOTIFICATION_ID = 0xC0DE01
        const val DEFAULT_CHANNEL_ID = "capacitor_http_server"
        const val DEFAULT_CHANNEL_NAME = "HTTP Server"

        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_ICON = "icon"

        fun start(
            context: Context,
            title: String,
            text: String,
            channelId: String?,
            channelName: String?,
            iconResName: String?
        ) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                channelId?.let { putExtra(EXTRA_CHANNEL_ID, it) }
                channelName?.let { putExtra(EXTRA_CHANNEL_NAME, it) }
                iconResName?.let { putExtra(EXTRA_ICON, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HttpServerService::class.java))
        }
    }
}
