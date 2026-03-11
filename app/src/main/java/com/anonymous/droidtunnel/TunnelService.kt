package com.anonymous.droidtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

class TunnelService : Service() {
    private var process: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (token.isBlank()) {
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startTunnel(token)
                return START_STICKY
            }
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val savedToken = readSavedToken()
                if (savedToken.isNotBlank()) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startTunnel(savedToken)
                    return START_STICKY
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTunnel(token: String) {
        stopTunnel()
        val binaryFile = prepareBinary() ?: return
        try {
            process = ProcessBuilder(
                binaryFile.absolutePath,
                "tunnel",
                "--no-autoupdate",
                "run",
                "--token",
                token
            ).redirectErrorStream(true)
                .start()
            isRunning = true
        } catch (e: IOException) {
            isRunning = false
            stopSelf()
        }
    }

    private fun stopTunnel() {
        process?.destroy()
        process = null
        isRunning = false
    }

    private fun prepareBinary(): File? {
        val abi = SUPPORTED_ABIS.firstOrNull { Build.SUPPORTED_ABIS.contains(it) } ?: return null
        val assetPath = "bin/$abi/cloudflared"
        val outputFile = File(filesDir, "cloudflared-$abi")
        return try {
            assets.open(assetPath).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outputFile.setExecutable(true, true)
            outputFile
        } catch (e: IOException) {
            null
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun readSavedToken(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_TOKEN, "") ?: ""
    }

    companion object {
        const val PREFS_NAME = "droid_tunnel"
        const val KEY_TOKEN = "token"
        private const val ACTION_START = "com.anonymous.droidtunnel.START"
        private const val ACTION_STOP = "com.anonymous.droidtunnel.STOP"
        private const val EXTRA_TOKEN = "extra_token"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1001
        private val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, token: String) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
