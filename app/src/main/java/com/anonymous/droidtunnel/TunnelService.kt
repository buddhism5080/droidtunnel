package com.anonymous.droidtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

class TunnelService : Service() {
    private var process: Process? = null
    private var logThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (token.isBlank()) {
                    appendLog("Token 为空，未启动")
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
        stopTunnel(false)
        appendLog("准备启动隧道")
        val binaryFile = prepareBinary()
        if (binaryFile == null) {
            appendLog("未找到可执行文件")
            stopSelf()
            return
        }
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
            appendLog("隧道已启动")
            process?.let { startLogReader(it) }
        } catch (e: IOException) {
            appendLog("启动失败: ${e.message}")
            isRunning = false
            stopSelf()
        }
    }

    private fun stopTunnel(log: Boolean = true) {
        process?.destroy()
        process = null
        logThread?.interrupt()
        logThread = null
        isRunning = false
        if (log) {
            appendLog("已停止隧道")
        }
    }

    private fun startLogReader(process: Process) {
        logThread?.interrupt()
        logThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> appendLog(line) }
                }
            } catch (e: IOException) {
                appendLog("读取日志失败: ${e.message}")
            } finally {
                appendLog("隧道进程已退出")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun prepareBinary(): File? {
        val nativeDir = applicationInfo.nativeLibraryDir
        if (nativeDir.isNullOrBlank()) {
            appendLog("nativeLibraryDir 不可用")
            return null
        }
        val binaryFile = File(nativeDir, NATIVE_BINARY_NAME)
        if (!binaryFile.exists()) {
            appendLog("未找到可执行文件: ${binaryFile.absolutePath}")
            return null
        }
        appendLog("已定位可执行文件: ${binaryFile.absolutePath}")
        return binaryFile
    }

    private fun appendLog(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val safeLine = if (trimmed.length > MAX_LOG_LINE_LENGTH) {
            trimmed.take(MAX_LOG_LINE_LENGTH) + "…"
        } else {
            trimmed
        }
        val snapshot = synchronized(logBuffer) {
            logBuffer.addLast(safeLine)
            while (logBuffer.size > MAX_LOG_LINES) {
                logBuffer.removeFirst()
            }
            logBuffer.joinToString("\n")
        }
        val intent = Intent(ACTION_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOGS, snapshot)
        }
        sendBroadcast(intent)
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
        const val ACTION_LOG = "com.anonymous.droidtunnel.LOG"
        const val EXTRA_LOGS = "extra_logs"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1001
        private const val NATIVE_BINARY_NAME = "libcloudflared.so"
        private const val MAX_LOG_LINES = 200
        private const val MAX_LOG_LINE_LENGTH = 500
        private val logBuffer: ArrayDeque<String> = ArrayDeque()

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

        fun getLogSnapshot(): String {
            synchronized(logBuffer) {
                return logBuffer.joinToString("\n")
            }
        }
    }
}
