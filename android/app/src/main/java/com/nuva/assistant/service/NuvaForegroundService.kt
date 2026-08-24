package com.nuva.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Microphone foreground session: while NUVA is actively listening a visible
 * notification says so (§26 — never silent recording). The service stops
 * itself the moment listening ends.
 */
class NuvaForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Listening status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when NUVA is actively listening to you."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NUVA is listening")
            .setContentText("Tap the mic again or say your command.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "nuva_listening"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, NuvaForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NuvaForegroundService::class.java))
        }
    }
}
