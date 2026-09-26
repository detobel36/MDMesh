package com.mdmesh.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service required by Android 10+ (API 29+) for MediaProjection screen capture.
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundNotification()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureService", "Failed to start foreground notification in onCreate: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundNotification()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureService", "Failed to start foreground notification in onStartCommand: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val channelId = "mdm_screen_capture"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Viewing Session",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("Live Screen Viewing")
            .setContentText("Screen is being viewed remotely by administrator")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 8801

        fun startService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenCaptureService", "Failed to start ScreenCaptureService: ${e.message}", e)
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            } catch (e: Exception) {
                android.util.Log.e("ScreenCaptureService", "Failed to stop ScreenCaptureService: ${e.message}", e)
            }
        }
    }
}
