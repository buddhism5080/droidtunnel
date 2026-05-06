package com.anonymous.droidtunnel

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TunnelService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restartPolicy = RestartPolicy()
    private val processMutex = Mutex()

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager

    private var process: Process? = null
    private var processGeneration: Long = 0L
    private var logJob: Job? = null
    private var exitWatcherJob: Job? = null
    private var healthJob: Job? = null
    private var restartJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var restartAttempt: Int = 0
    private var notificationStarted = false
    private var lastProcessOutputAtMillis: Long = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkStateChanged("网络已可用")
        }

        override fun onLost(network: Network) {
            onNetworkStateChanged("网络已断开")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                onNetworkStateChanged("网络已验证")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        resetRuntimeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestIntent = intent ?: Intent()
        val action = requestIntent.action ?: ACTION_ENSURE
        when (action) {
            ACTION_STOP -> {
                handleUserStop()
                return START_NOT_STICKY
            }

            ACTION_START,
            ACTION_ENSURE,
            -> {
                val explicitToken = requestIntent.getStringExtra(EXTRA_TOKEN).orEmpty().trim()
                if (explicitToken.isNotBlank()) {
                    TunnelPreferences.saveToken(this, explicitToken)
                    TunnelPreferences.setDesiredRunning(this, true)
                }
                ensureForeground()
                serviceScope.launch {
                    ensureTunnelRunning(
                        reason = requestIntent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank {
                            if (action == ACTION_START) "用户请求启动" else "守护唤醒"
                        },
                    )
                }
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (TunnelPreferences.readDesiredRunning(this) && TunnelPreferences.readToken(this).isNotBlank()) {
            scheduleServiceWakeup(delayMs = 5_000L, reason = "任务移除后守护恢复")
        }
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        restartJob?.cancel()
        healthJob?.cancel()
        exitWatcherJob?.cancel()
        logJob?.cancel()
        networkRecoveryJob?.cancel()
        runCatching {
            process?.destroy()
        }
        process = null
        notificationStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleUserStop() {
        TunnelPreferences.setDesiredRunning(this, false)
        appendLog("用户请求停止隧道")
        serviceScope.launch {
            processMutex.withLock {
                stopCurrentProcess(reason = "用户已停止", clearDesiredRunning = true)
            }
            updateRuntimeState {
                it.copy(
                    desiredRunning = false,
                    status = TunnelStatus.STOPPED,
                    statusMessage = "已停止",
                    nextRestartAtMillis = null,
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationStarted = false
            stopSelf()
        }
    }

    private suspend fun ensureTunnelRunning(reason: String) {
        processMutex.withLock {
            val token = TunnelPreferences.readToken(this)
            val desiredRunning = TunnelPreferences.readDesiredRunning(this)
            if (!desiredRunning) {
                updateRuntimeState {
                    it.copy(
                        desiredRunning = false,
                        status = TunnelStatus.STOPPED,
                        statusMessage = "已停止",
                    )
                }
                return
            }

            if (token.isBlank()) {
                appendLog("未检测到 Cloudflare Tunnel Token，无法启动")
                updateRuntimeState {
                    it.copy(
                        desiredRunning = true,
                        status = TunnelStatus.ERROR,
                        statusMessage = "缺少 Tunnel Token",
                    )
                }
                return
            }

            if (process?.isAlive == true) {
                appendLog("收到守护请求：$reason")
                if (runtimeState.value.status == TunnelStatus.DEGRADED) {
                    serviceScope.launch { performSingleHealthCheck("守护触发补检") }
                }
                return
            }

            launchTunnel(token = token, reason = reason)
        }
    }

    private suspend fun launchTunnel(token: String, reason: String) {
        restartJob?.cancel()
        val binaryFile = prepareBinary() ?: run {
            updateRuntimeState {
                it.copy(
                    desiredRunning = TunnelPreferences.readDesiredRunning(this),
                    status = TunnelStatus.ERROR,
                    statusMessage = "cloudflared 二进制不可用",
                )
            }
            return
        }

        stopCurrentProcess(reason = "切换到新的隧道进程", clearDesiredRunning = false)
        val args = buildCommand(binaryFile, token)
        appendLog("准备启动 cloudflared：$reason")
        appendLog(
            args.joinToString(separator = " ") { value ->
                if (value == token) "<redacted-token>" else value
            },
        )

        try {
            val startedProcess = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            process = startedProcess
            processGeneration += 1L
            val generation = processGeneration
            lastProcessOutputAtMillis = System.currentTimeMillis()
            val now = System.currentTimeMillis()

            updateRuntimeState {
                it.copy(
                    desiredRunning = true,
                    status = TunnelStatus.STARTING,
                    statusMessage = "正在与 Cloudflare Edge 建立连接",
                    readyConnections = 0,
                    consecutiveProbeFailures = 0,
                    lastStartedAtMillis = now,
                    nextRestartAtMillis = null,
                    binaryPath = binaryFile.absolutePath,
                )
            }
            refreshNotification()

            startLogReader(generation, startedProcess)
            startExitWatcher(generation, startedProcess)
            startHealthLoop(generation)
        } catch (e: IOException) {
            appendLog("启动失败：${e.message.orEmpty()}")
            updateRuntimeState {
                it.copy(
                    desiredRunning = true,
                    status = TunnelStatus.ERROR,
                    statusMessage = "启动失败：${e.message.orEmpty()}",
                )
            }
            scheduleRestart(reason = "cloudflared 启动失败", authFailure = false, stopProcessFirst = false)
        }
    }

    private fun buildCommand(binaryFile: File, token: String): List<String> {
        val args = mutableListOf(
            binaryFile.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--protocol",
            "http2",
        )

        val ipv6Probe = probeRegion1Ipv6Connectivity()
        if (ipv6Probe.reachable) {
            appendLog(
                "IPv6 直连探测通过：DNS ${ipv6Probe.dnsServer} -> ${ipv6Probe.address}:$ARGO_EDGE_PORT，优先使用 IPv6 Edge",
            )
            args += listOf("--edge-ip-version", "6")
        } else {
            appendLog("IPv6 直连探测未通过：${ipv6Probe.summary}，保持 cloudflared 默认 Edge 策略")
        }

        args += listOf(
            "--metrics",
            METRICS_ENDPOINT,
            "run",
            "--token",
            token,
        )
        return args
    }

    private fun startLogReader(generation: Long, process: Process) {
        logJob?.cancel()
        logJob = serviceScope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!isActive || generation != processGeneration) {
                            return@forEach
                        }
                        lastProcessOutputAtMillis = System.currentTimeMillis()
                        appendLog(line)
                    }
                }
            } catch (e: IOException) {
                appendLog("读取进程日志失败：${e.message.orEmpty()}")
            }
        }
    }

    private fun startExitWatcher(generation: Long, runningProcess: Process) {
        exitWatcherJob?.cancel()
        exitWatcherJob = serviceScope.launch {
            val exitCode = runCatching { runningProcess.waitFor() }.getOrDefault(-1)
            if (generation != processGeneration) {
                return@launch
            }

            appendLog("隧道进程退出，exitCode=$exitCode")
            updateRuntimeState {
                it.copy(
                    readyConnections = 0,
                    consecutiveProbeFailures = 0,
                    lastExitReason = "进程退出，exitCode=$exitCode",
                    status = if (TunnelPreferences.readDesiredRunning(this@TunnelService)) {
                        TunnelStatus.DEGRADED
                    } else {
                        TunnelStatus.STOPPED
                    },
                    statusMessage = if (TunnelPreferences.readDesiredRunning(this@TunnelService)) {
                        "隧道意外退出，准备恢复"
                    } else {
                        "已停止"
                    },
                )
            }
            refreshNotification()
            processMutex.withLock {
                this@TunnelService.process = null
                logJob?.cancel()
                healthJob?.cancel()
                if (TunnelPreferences.readDesiredRunning(this@TunnelService)) {
                    scheduleRestart(reason = "cloudflared 进程退出", authFailure = false, stopProcessFirst = false)
                }
            }
        }
    }

    private fun startHealthLoop(generation: Long) {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            delay(HEALTH_STARTUP_GRACE_MS)
            while (isActive && generation == processGeneration && TunnelPreferences.readDesiredRunning(this@TunnelService)) {
                val shouldContinue = performSingleHealthCheck(reason = "定时巡检")
                if (!shouldContinue) {
                    return@launch
                }

                val probeFailures = runtimeState.value.consecutiveProbeFailures
                val interval = if (probeFailures == 0) {
                    HEALTHY_PROBE_INTERVAL_MS
                } else {
                    DEGRADED_PROBE_INTERVAL_MS
                }
                delay(interval)
            }
        }
    }

    private suspend fun performSingleHealthCheck(reason: String): Boolean {
        val currentProcess = process
        if (currentProcess == null || !currentProcess.isAlive) {
            scheduleRestart(reason = "$reason：检测到进程不存在", authFailure = false, stopProcessFirst = false)
            return false
        }

        val result = probeReadiness()
        val now = System.currentTimeMillis()
        if (result.ready) {
            restartAttempt = 0
            updateRuntimeState {
                it.copy(
                    desiredRunning = true,
                    status = TunnelStatus.HEALTHY,
                    statusMessage = "Tunnel 可用，readyConnections=${result.readyConnections}",
                    readyConnections = result.readyConnections,
                    consecutiveProbeFailures = 0,
                    lastHealthyAtMillis = now,
                    nextRestartAtMillis = null,
                )
            }
            refreshNotification()
            return true
        }

        val newFailures = runtimeState.value.consecutiveProbeFailures + 1
        updateRuntimeState {
            it.copy(
                desiredRunning = true,
                status = TunnelStatus.DEGRADED,
                statusMessage = "健康检查失败：${result.summary}",
                readyConnections = 0,
                consecutiveProbeFailures = newFailures,
            )
        }
        refreshNotification()

        if (newFailures >= MAX_CONSECUTIVE_PROBE_FAILURES) {
            appendLog("连续 $newFailures 次健康检查失败，准备重启隧道")
            scheduleRestart(
                reason = "$reason：健康检查连续失败",
                authFailure = false,
                stopProcessFirst = true,
            )
            return false
        }

        val silenceDuration = now - lastProcessOutputAtMillis
        if (silenceDuration >= MAX_PROCESS_SILENCE_MS) {
            appendLog("进程长时间无输出（${silenceDuration / 1000}s），主动重启")
            scheduleRestart(
                reason = "$reason：进程长时间无输出",
                authFailure = false,
                stopProcessFirst = true,
            )
            return false
        }

        return true
    }

    private fun scheduleRestart(
        reason: String,
        authFailure: Boolean,
        stopProcessFirst: Boolean,
    ) {
        if (!TunnelPreferences.readDesiredRunning(this)) {
            appendLog("忽略重启：当前不要求保持运行")
            return
        }

        serviceScope.launch {
            processMutex.withLock {
                if (stopProcessFirst) {
                    stopCurrentProcess(reason = "准备重启：$reason", clearDesiredRunning = false)
                }

                restartJob?.cancel()
                val baseDelay = restartPolicy.nextBaseDelayMs(
                    attempt = restartAttempt,
                    authFailure = authFailure,
                )
                val delayMs = restartPolicy.applyJitter(
                    baseDelayMs = baseDelay,
                    randomFactor = Random.nextDouble(),
                )
                val nextRestartAtMillis = System.currentTimeMillis() + delayMs
                restartAttempt += 1

                updateRuntimeState {
                    it.copy(
                        desiredRunning = true,
                        status = TunnelStatus.RESTARTING,
                        statusMessage = "正在安排重连：$reason",
                        nextRestartAtMillis = nextRestartAtMillis,
                        lastExitReason = reason,
                        restartCount = it.restartCount + 1,
                    )
                }
                refreshNotification()
                appendLog("将在 ${delayMs / 1000.0} 秒后重连：$reason")

                restartJob = serviceScope.launch {
                    delay(delayMs)
                    processMutex.withLock {
                        val token = TunnelPreferences.readToken(this@TunnelService)
                        if (!TunnelPreferences.readDesiredRunning(this@TunnelService) || token.isBlank()) {
                            appendLog("取消重连：Token 或目标状态不可用")
                            return@withLock
                        }
                        launchTunnel(token = token, reason = "自动恢复：$reason")
                    }
                }
            }
        }
    }

    private suspend fun stopCurrentProcess(
        reason: String,
        clearDesiredRunning: Boolean,
    ) {
        restartJob?.cancel()
        healthJob?.cancel()
        exitWatcherJob?.cancel()
        logJob?.cancel()

        val currentProcess = process
        process = null
        if (clearDesiredRunning) {
            TunnelPreferences.setDesiredRunning(this, false)
        }

        if (currentProcess != null) {
            runCatching {
                currentProcess.destroy()
                if (!currentProcess.waitFor(1_200L, TimeUnit.MILLISECONDS)) {
                    currentProcess.destroyForcibly()
                }
            }
        }

        updateRuntimeState {
            it.copy(
                desiredRunning = if (clearDesiredRunning) false else TunnelPreferences.readDesiredRunning(this),
                readyConnections = 0,
                consecutiveProbeFailures = 0,
                nextRestartAtMillis = null,
                lastExitReason = reason,
            )
        }
        refreshNotification()
    }

    private fun onNetworkStateChanged(reason: String) {
        appendLog(reason)
        if (!TunnelPreferences.readDesiredRunning(this)) {
            return
        }

        networkRecoveryJob?.cancel()
        networkRecoveryJob = serviceScope.launch {
            delay(NETWORK_EVENT_DEBOUNCE_MS)
            processMutex.withLock {
                val currentProcess = process
                if (currentProcess == null || !currentProcess.isAlive) {
                    appendLog("网络恢复后未发现活动进程，立即尝试恢复")
                    val token = TunnelPreferences.readToken(this@TunnelService)
                    if (token.isNotBlank()) {
                        launchTunnel(token = token, reason = reason)
                    }
                    return@withLock
                }
            }
            performSingleHealthCheck(reason = reason)
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
            appendLog("未找到 cloudflared：${binaryFile.absolutePath}")
            return null
        }
        if (binaryFile.length() < MIN_BINARY_SIZE_BYTES) {
            appendLog("cloudflared 二进制尺寸异常，疑似占位文件：${binaryFile.length()} bytes")
            return null
        }

        return binaryFile
    }

    private fun probeReadiness(): HealthResult {
        val connection = runCatching {
            (URL(READY_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_000
                readTimeout = 2_000
                useCaches = false
            }
        }.getOrElse {
            return HealthResult(false, 0, "无法连接 readiness 端点")
        }

        return try {
            val statusCode = connection.responseCode
            val responseBody = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (statusCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(responseBody)
                val readyConnections = json.optInt("readyConnections", 0)
                val ready = readyConnections > 0
                if (ready) {
                    HealthResult(true, readyConnections, "readyConnections=$readyConnections")
                } else {
                    HealthResult(false, readyConnections, "readyConnections=0")
                }
            } else {
                HealthResult(false, 0, "HTTP $statusCode")
            }
        } catch (e: Exception) {
            HealthResult(false, 0, e.message.orEmpty().ifBlank { "readiness 检查失败" })
        } finally {
            connection.disconnect()
        }
    }

    private fun probeRegion1Ipv6Connectivity(): Ipv6ProbeResult {
        val activeNetwork = connectivityManager.activeNetwork
            ?: return Ipv6ProbeResult(reachable = false, summary = "当前没有活动网络")
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            ?: return Ipv6ProbeResult(reachable = false, summary = "无法读取当前网络属性")
        val dnsServers = linkProperties.dnsServers
            .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
            .distinctBy { it.hostAddress }

        if (dnsServers.isEmpty()) {
            return Ipv6ProbeResult(
                reachable = false,
                summary = "当前网络没有可直连的 DNS 服务器（已过滤回环地址）",
            )
        }

        val failureReasons = mutableListOf<String>()
        for (dnsServer in dnsServers) {
            val ipv6Answers = try {
                queryAaaaRecords(
                    network = activeNetwork,
                    dnsServer = dnsServer,
                    hostname = IPV6_PROBE_HOST,
                )
            } catch (e: IOException) {
                failureReasons += "DNS ${dnsServer.hostAddress} 解析失败：${e.message.orEmpty().ifBlank { "未知错误" }}"
                continue
            }

            if (ipv6Answers.isEmpty()) {
                failureReasons += "DNS ${dnsServer.hostAddress} 未返回 $IPV6_PROBE_HOST 的 AAAA 记录"
                continue
            }

            for (ipv6Address in ipv6Answers) {
                if (canConnectToEdgeOverIpv6(activeNetwork, ipv6Address, ARGO_EDGE_PORT)) {
                    return Ipv6ProbeResult(
                        reachable = true,
                        dnsServer = dnsServer.hostAddress,
                        address = ipv6Address.hostAddress,
                        summary = "通过 ${dnsServer.hostAddress} 成功命中 ${ipv6Address.hostAddress}",
                    )
                }
            }

            failureReasons += "DNS ${dnsServer.hostAddress} 返回 ${ipv6Answers.size} 个 AAAA，但 $ARGO_EDGE_PORT 端口均不可达"
        }

        return Ipv6ProbeResult(
            reachable = false,
            summary = failureReasons.joinToString(separator = "；").take(260).ifBlank {
                "未解析到可用的 IPv6 Edge"
            },
        )
    }

    private fun queryAaaaRecords(
        network: Network,
        dnsServer: InetAddress,
        hostname: String,
    ): List<Inet6Address> {
        val queryId = Random.nextInt(0, 0x10000)
        val queryPacket = buildDnsQuery(
            hostname = hostname,
            queryId = queryId,
            queryType = DNS_TYPE_AAAA,
        )
        val responseBuffer = ByteArray(DNS_RESPONSE_BUFFER_BYTES)

        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = DNS_QUERY_TIMEOUT_MS.toInt()
            socket.connect(dnsServer, DNS_SERVER_PORT)
            val request = DatagramPacket(queryPacket, queryPacket.size)
            socket.send(request)

            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(response)
            return parseAaaaAnswers(
                payload = response.data,
                length = response.length,
                expectedQueryId = queryId,
            )
        }
    }

    private fun buildDnsQuery(
        hostname: String,
        queryId: Int,
        queryType: Int,
    ): ByteArray {
        val labels = hostname.trim('.').split('.').filter { it.isNotBlank() }
        if (labels.isEmpty()) {
            throw IOException("DNS 查询域名为空")
        }

        return ByteArrayOutputStream().apply {
            writeUnsignedShort(this, queryId)
            writeUnsignedShort(this, 0x0100)
            writeUnsignedShort(this, 1)
            writeUnsignedShort(this, 0)
            writeUnsignedShort(this, 0)
            writeUnsignedShort(this, 0)

            labels.forEach { label ->
                val labelBytes = label.toByteArray(Charsets.UTF_8)
                if (labelBytes.isEmpty() || labelBytes.size > 63) {
                    throw IOException("非法 DNS label：$label")
                }
                write(labelBytes.size)
                write(labelBytes)
            }
            write(0)
            writeUnsignedShort(this, queryType)
            writeUnsignedShort(this, DNS_CLASS_IN)
        }.toByteArray()
    }

    private fun parseAaaaAnswers(
        payload: ByteArray,
        length: Int,
        expectedQueryId: Int,
    ): List<Inet6Address> {
        if (length < DNS_HEADER_LENGTH) {
            throw IOException("DNS 响应过短")
        }

        val responseId = readUnsignedShort(payload, 0)
        if (responseId != expectedQueryId) {
            throw IOException("DNS 响应 ID 不匹配")
        }

        val flags = readUnsignedShort(payload, 2)
        val rCode = flags and 0x000F
        if (rCode != 0) {
            throw IOException("DNS 响应错误，rcode=$rCode")
        }

        val questionCount = readUnsignedShort(payload, 4)
        val answerCount = readUnsignedShort(payload, 6)
        var offset = DNS_HEADER_LENGTH

        repeat(questionCount) {
            offset = skipDnsName(payload, length, offset)
            if (offset + 4 > length) {
                throw IOException("DNS question 区域截断")
            }
            offset += 4
        }

        val answers = linkedSetOf<Inet6Address>()
        repeat(answerCount) {
            offset = skipDnsName(payload, length, offset)
            if (offset + 10 > length) {
                throw IOException("DNS answer 区域截断")
            }

            val type = readUnsignedShort(payload, offset)
            val clazz = readUnsignedShort(payload, offset + 2)
            val rdLength = readUnsignedShort(payload, offset + 8)
            offset += 10

            if (offset + rdLength > length) {
                throw IOException("DNS RDATA 截断")
            }

            if (type == DNS_TYPE_AAAA && clazz == DNS_CLASS_IN && rdLength == 16) {
                val addressBytes = payload.copyOfRange(offset, offset + rdLength)
                val address = InetAddress.getByAddress(addressBytes)
                if (address is Inet6Address) {
                    answers += address
                }
            }
            offset += rdLength
        }

        return answers.toList()
    }

    private fun skipDnsName(
        payload: ByteArray,
        length: Int,
        startOffset: Int,
    ): Int {
        var offset = startOffset
        var steps = 0
        while (offset < length) {
            if (++steps > 128) {
                throw IOException("DNS 名称跳转过深")
            }
            val marker = payload[offset].toInt() and 0xFF
            when {
                marker == 0 -> return offset + 1
                marker and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= length) {
                        throw IOException("DNS 压缩指针截断")
                    }
                    return offset + 2
                }
                else -> {
                    offset += 1
                    if (offset + marker > length) {
                        throw IOException("DNS label 越界")
                    }
                    offset += marker
                }
            }
        }
        throw IOException("DNS 名称解析越界")
    }

    private fun canConnectToEdgeOverIpv6(
        network: Network,
        address: Inet6Address,
        port: Int,
    ): Boolean {
        return try {
            Socket().use { socket ->
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(address, port), IPV6_CONNECT_TIMEOUT_MS.toInt())
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun writeUnsignedShort(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun readUnsignedShort(payload: ByteArray, offset: Int): Int {
        return ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
    }

    private fun ensureForeground() {
        if (!notificationStarted) {
            startForeground(NOTIFICATION_ID, buildNotification())
            notificationStarted = true
        } else {
            refreshNotification()
        }
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = runtimeState.value
        val contentText = state.statusMessage.ifBlank { "正在后台维持 Tunnel 可用性" }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.disconnect), stopIntent)
            .build()
    }

    private fun refreshNotification() {
        if (!notificationStarted) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureNotificationChannel() {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun appendLog(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return
        }

        val safeLine = trimmed.take(MAX_LOG_LINE_LENGTH)
        val timestamp = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.CHINA)
            .format(Date())
        val combined = "$timestamp  $safeLine"

        updateRuntimeState {
            val nextLogs = (it.logs + combined).takeLast(MAX_LOG_LINES)
            it.copy(logs = nextLogs)
        }
        refreshNotification()
    }

    private fun updateRuntimeState(transform: (TunnelRuntimeState) -> TunnelRuntimeState) {
        _runtimeState.update(transform)
    }

    private fun resetRuntimeState() {
        _runtimeState.value = TunnelRuntimeState(
            desiredRunning = TunnelPreferences.readDesiredRunning(this),
            status = TunnelStatus.STOPPED,
            statusMessage = if (TunnelPreferences.readDesiredRunning(this)) {
                "等待守护恢复"
            } else {
                "待命中"
            },
            logs = emptyList(),
        )
    }

    private fun scheduleServiceWakeup(delayMs: Long, reason: String) {
        val intent = Intent(this, TunnelService::class.java).apply {
            action = ACTION_ENSURE
            putExtra(EXTRA_REASON, reason)
        }
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent,
        )
    }

    data class HealthResult(
        val ready: Boolean,
        val readyConnections: Int,
        val summary: String,
    )

    data class Ipv6ProbeResult(
        val reachable: Boolean,
        val dnsServer: String? = null,
        val address: String? = null,
        val summary: String,
    )

    companion object {
        private const val ACTION_START = "com.anonymous.droidtunnel.START"
        private const val ACTION_STOP = "com.anonymous.droidtunnel.STOP"
        private const val ACTION_ENSURE = "com.anonymous.droidtunnel.ENSURE"
        private const val EXTRA_TOKEN = "extra_token"
        private const val EXTRA_REASON = "extra_reason"
        private const val NOTIFICATION_CHANNEL_ID = "tunnel_guard"
        private const val NOTIFICATION_ID = 1001
        private const val NATIVE_BINARY_NAME = "libcloudflared.so"
        private const val METRICS_PORT = 45000
        private const val METRICS_ENDPOINT = "127.0.0.1:$METRICS_PORT"
        private const val READY_URL = "http://$METRICS_ENDPOINT/ready"
        private const val IPV6_PROBE_HOST = "region1.v2.argotunnel.com"
        private const val ARGO_EDGE_PORT = 7844
        private const val DNS_SERVER_PORT = 53
        private const val DNS_HEADER_LENGTH = 12
        private const val DNS_CLASS_IN = 1
        private const val DNS_TYPE_AAAA = 28
        private const val DNS_QUERY_TIMEOUT_MS = 2_000L
        private const val DNS_RESPONSE_BUFFER_BYTES = 1500
        private const val IPV6_CONNECT_TIMEOUT_MS = 2_000L
        private const val MAX_LOG_LINES = 240
        private const val MAX_LOG_LINE_LENGTH = 600
        private const val MIN_BINARY_SIZE_BYTES = 1_000_000L
        private const val HEALTH_STARTUP_GRACE_MS = 15_000L
        private const val HEALTHY_PROBE_INTERVAL_MS = 12_000L
        private const val DEGRADED_PROBE_INTERVAL_MS = 4_000L
        private const val MAX_CONSECUTIVE_PROBE_FAILURES = 3
        private const val MAX_PROCESS_SILENCE_MS = 90_000L
        private const val NETWORK_EVENT_DEBOUNCE_MS = 2_500L

        private val _runtimeState = MutableStateFlow(TunnelRuntimeState())
        val runtimeState: StateFlow<TunnelRuntimeState> = _runtimeState.asStateFlow()

        fun start(context: Context, token: String) {
            TunnelPreferences.saveToken(context, token)
            TunnelPreferences.setDesiredRunning(context, true)
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_REASON, "用户点击连接")
            }
            startForegroundServiceSafely(context, intent)
        }

        fun ensureRunning(context: Context, reason: String) {
            if (TunnelPreferences.readToken(context).isBlank()) {
                return
            }
            TunnelPreferences.setDesiredRunning(context, true)
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_ENSURE
                putExtra(EXTRA_REASON, reason)
            }
            startForegroundServiceSafely(context, intent)
        }

        fun stop(context: Context) {
            TunnelPreferences.setDesiredRunning(context, false)
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun startForegroundServiceSafely(context: Context, intent: Intent) {
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                scheduleFallbackWakeup(context, intent)
            }
        }

        private fun scheduleFallbackWakeup(context: Context, intent: Intent) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = PendingIntent.getForegroundService(
                context,
                9,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 15_000L,
                pendingIntent,
            )
        }
    }
}
