package com.anonymous.droidtunnel

class RestartPolicy(
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 60_000L,
    private val authFailureDelayMs: Long = 30_000L,
) {
    fun nextBaseDelayMs(attempt: Int, authFailure: Boolean = false): Long {
        if (authFailure) {
            return authFailureDelayMs
        }

        val safeAttempt = attempt.coerceIn(0, 8)
        val rawDelay = baseDelayMs * (1L shl safeAttempt)
        return rawDelay.coerceAtMost(maxDelayMs)
    }

    fun applyJitter(
        baseDelayMs: Long,
        jitterFraction: Double = 0.15,
        randomFactor: Double,
    ): Long {
        if (jitterFraction <= 0) {
            return baseDelayMs
        }

        val boundedRandomFactor = randomFactor.coerceIn(0.0, 1.0)
        val maxDelta = (baseDelayMs * jitterFraction).toLong()
        if (maxDelta <= 0L) {
            return baseDelayMs
        }

        val signedFactor = (boundedRandomFactor * 2.0) - 1.0
        val delta = (maxDelta * signedFactor).toLong()
        return (baseDelayMs + delta).coerceIn(this.baseDelayMs, maxDelayMs)
    }
}
