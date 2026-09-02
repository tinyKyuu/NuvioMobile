package com.nuvio.app.features.watchtogether.protocol

internal data class RelayClockSample(
    val localSentAtMs: Long,
    val localReceivedAtMs: Long,
    val relayTimeMs: Long,
) {
    init {
        require(localSentAtMs >= 0L)
        require(localReceivedAtMs >= localSentAtMs)
        require(relayTimeMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER)
    }

    val roundTripMs: Long = localReceivedAtMs - localSentAtMs
    val localMidpointMs: Long = localSentAtMs + roundTripMs / 2L
    val offsetMs: Long = relayTimeMs - localMidpointMs
}

internal data class RelayClockEstimate(
    val offsetMs: Long,
    val roundTripMs: Long,
    val sampleCount: Int,
)

internal class RelayClockEstimator(
    private val capacity: Int = 8,
) {
    private data class RecordedSample(
        val sample: RelayClockSample,
        val order: Long,
    )

    private val samples = ArrayDeque<RecordedSample>()
    private var nextOrder = 0L

    init {
        require(capacity > 0)
    }

    fun record(sample: RelayClockSample): RelayClockEstimate {
        samples.addLast(RecordedSample(sample = sample, order = nextOrder++))
        while (samples.size > capacity) samples.removeFirst()
        return estimate()
    }

    fun estimate(): RelayClockEstimate {
        val best = samples.minWithOrNull(
            compareBy<RecordedSample> { it.sample.roundTripMs }
                .thenByDescending { it.order },
        ) ?: error("relay clock has no samples")
        return RelayClockEstimate(
            offsetMs = best.sample.offsetMs,
            roundTripMs = best.sample.roundTripMs,
            sampleCount = samples.size,
        )
    }

    fun relayTimeAt(localMonotonicTimeMs: Long): Long? {
        if (samples.isEmpty() || localMonotonicTimeMs < 0L) return null
        val offsetMs = estimate().offsetMs
        if (offsetMs > 0L && localMonotonicTimeMs > Long.MAX_VALUE - offsetMs) return null
        if (offsetMs < 0L && localMonotonicTimeMs < -offsetMs) return null
        return (localMonotonicTimeMs + offsetMs)
            .takeIf { it in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER }
    }

    fun reset() {
        samples.clear()
        nextOrder = 0L
    }
}
