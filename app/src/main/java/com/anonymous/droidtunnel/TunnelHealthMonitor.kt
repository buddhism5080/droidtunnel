package com.anonymous.droidtunnel

internal data class NotReadyAssessment(
    val status: TunnelStatus,
    val statusMessage: String,
    val consecutiveProbeFailures: Int,
)

internal object TunnelHealthMonitor {
    const val STARTUP_READY_TIMEOUT_MS = 60_000L

    fun assessNotReady(
        summary: String,
        previousFailures: Int,
        now: Long,
        lastStartedAtMillis: Long?,
        lastHealthyAtMillis: Long?,
    ): NotReadyAssessment {
        val startedAt = lastStartedAtMillis
        val hasHealthyConnectionSinceStart = startedAt != null &&
            lastHealthyAtMillis != null &&
            lastHealthyAtMillis >= startedAt
        val withinStartupWarmup = startedAt != null &&
            !hasHealthyConnectionSinceStart &&
            now - startedAt < STARTUP_READY_TIMEOUT_MS

        return if (withinStartupWarmup) {
            NotReadyAssessment(
                status = TunnelStatus.STARTING,
                statusMessage = "隧道启动中：$summary",
                consecutiveProbeFailures = 0,
            )
        } else {
            NotReadyAssessment(
                status = TunnelStatus.DEGRADED,
                statusMessage = "健康检查失败：$summary",
                consecutiveProbeFailures = previousFailures + 1,
            )
        }
    }
}
