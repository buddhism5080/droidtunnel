package com.anonymous.droidtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {
    private val policy = RestartPolicy(
        baseDelayMs = 2_000L,
        maxDelayMs = 60_000L,
        authFailureDelayMs = 30_000L,
    )

    @Test
    fun `uses exponential backoff for transient failures`() {
        assertEquals(2_000L, policy.nextBaseDelayMs(attempt = 0))
        assertEquals(4_000L, policy.nextBaseDelayMs(attempt = 1))
        assertEquals(8_000L, policy.nextBaseDelayMs(attempt = 2))
    }

    @Test
    fun `caps exponential backoff at configured maximum`() {
        assertEquals(60_000L, policy.nextBaseDelayMs(attempt = 8))
        assertEquals(60_000L, policy.nextBaseDelayMs(attempt = 32))
    }

    @Test
    fun `auth failures jump to dedicated delay`() {
        assertEquals(30_000L, policy.nextBaseDelayMs(attempt = 0, authFailure = true))
        assertEquals(30_000L, policy.nextBaseDelayMs(attempt = 5, authFailure = true))
    }

    @Test
    fun `jitter stays inside allowed bounds`() {
        val low = policy.applyJitter(baseDelayMs = 10_000L, jitterFraction = 0.2, randomFactor = 0.0)
        val mid = policy.applyJitter(baseDelayMs = 10_000L, jitterFraction = 0.2, randomFactor = 0.5)
        val high = policy.applyJitter(baseDelayMs = 10_000L, jitterFraction = 0.2, randomFactor = 1.0)

        assertTrue(low in 8_000L..12_000L)
        assertEquals(10_000L, mid)
        assertTrue(high in 8_000L..12_000L)
    }
}
