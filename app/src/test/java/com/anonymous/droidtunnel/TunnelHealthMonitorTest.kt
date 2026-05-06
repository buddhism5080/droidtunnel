package com.anonymous.droidtunnel

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelHealthMonitorTest {
    @Test
    fun `keeps startup state during initial readiness warmup`() {
        val result = TunnelHealthMonitor.assessNotReady(
            summary = "HTTP 503，readyConnections=0",
            previousFailures = 2,
            now = 40_000L,
            lastStartedAtMillis = 0L,
            lastHealthyAtMillis = null,
        )

        assertEquals(TunnelStatus.STARTING, result.status)
        assertEquals("隧道启动中：HTTP 503，readyConnections=0", result.statusMessage)
        assertEquals(0, result.consecutiveProbeFailures)
    }

    @Test
    fun `starts counting failures after startup warmup expires`() {
        val result = TunnelHealthMonitor.assessNotReady(
            summary = "HTTP 503，readyConnections=0",
            previousFailures = 1,
            now = TunnelHealthMonitor.STARTUP_READY_TIMEOUT_MS + 5_000L,
            lastStartedAtMillis = 0L,
            lastHealthyAtMillis = null,
        )

        assertEquals(TunnelStatus.DEGRADED, result.status)
        assertEquals("健康检查失败：HTTP 503，readyConnections=0", result.statusMessage)
        assertEquals(2, result.consecutiveProbeFailures)
    }

    @Test
    fun `counts failures immediately after a previously healthy tunnel loses readiness`() {
        val result = TunnelHealthMonitor.assessNotReady(
            summary = "HTTP 503，readyConnections=0",
            previousFailures = 0,
            now = 20_000L,
            lastStartedAtMillis = 10_000L,
            lastHealthyAtMillis = 15_000L,
        )

        assertEquals(TunnelStatus.DEGRADED, result.status)
        assertEquals("健康检查失败：HTTP 503，readyConnections=0", result.statusMessage)
        assertEquals(1, result.consecutiveProbeFailures)
    }
}
