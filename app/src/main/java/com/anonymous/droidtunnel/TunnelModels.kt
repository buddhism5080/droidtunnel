package com.anonymous.droidtunnel

enum class TunnelStatus {
    STOPPED,
    STARTING,
    HEALTHY,
    DEGRADED,
    RESTARTING,
    ERROR,
}

data class TunnelRuntimeState(
    val desiredRunning: Boolean = false,
    val status: TunnelStatus = TunnelStatus.STOPPED,
    val statusMessage: String = "待命中",
    val readyConnections: Int = 0,
    val restartCount: Int = 0,
    val consecutiveProbeFailures: Int = 0,
    val lastStartedAtMillis: Long? = null,
    val lastHealthyAtMillis: Long? = null,
    val nextRestartAtMillis: Long? = null,
    val lastExitReason: String = "",
    val binaryPath: String = "",
    val logs: List<String> = emptyList(),
)

data class TunnelScreenState(
    val token: String = "",
    val showTokenEditor: Boolean = true,
    val batteryOptimizationRecommended: Boolean = false,
    val runtime: TunnelRuntimeState = TunnelRuntimeState(),
)
