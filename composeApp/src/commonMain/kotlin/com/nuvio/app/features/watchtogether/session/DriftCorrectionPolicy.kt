package com.nuvio.app.features.watchtogether.session

import kotlin.math.abs

internal sealed interface DriftCorrectionDecision {
    data object None : DriftCorrectionDecision
    data object HardSeek : DriftCorrectionDecision
    data class TemporaryRate(
        val rate: Double,
        val durationMs: Long,
    ) : DriftCorrectionDecision
}

internal class DriftCorrectionPolicy(
    private val toleranceMs: Long = 250L,
    private val hardSeekThresholdMs: Long = 1_500L,
    private val persistenceSamples: Int = 2,
    private val correctionDurationMs: Long = 6_000L,
    private val correctionCooldownMs: Long = 7_000L,
) {
    private var driftSign = 0
    private var driftSamples = 0
    private var cooldownUntilMs = 0L

    fun evaluate(driftMs: Long, localTimeMs: Long): DriftCorrectionDecision {
        val absoluteDriftMs = abs(driftMs)
        if (absoluteDriftMs <= toleranceMs) {
            resetPersistence()
            return DriftCorrectionDecision.None
        }
        if (absoluteDriftMs > hardSeekThresholdMs) {
            resetPersistence()
            return DriftCorrectionDecision.HardSeek
        }
        if (localTimeMs < cooldownUntilMs) return DriftCorrectionDecision.None

        val currentSign = if (driftMs > 0L) 1 else -1
        if (currentSign == driftSign) {
            driftSamples += 1
        } else {
            driftSign = currentSign
            driftSamples = 1
        }
        if (driftSamples < persistenceSamples) return DriftCorrectionDecision.None

        resetPersistence()
        cooldownUntilMs = localTimeMs + correctionCooldownMs
        return DriftCorrectionDecision.TemporaryRate(
            rate = if (driftMs > 0L) 0.97 else 1.03,
            durationMs = correctionDurationMs,
        )
    }

    fun reset() {
        resetPersistence()
        cooldownUntilMs = 0L
    }

    private fun resetPersistence() {
        driftSign = 0
        driftSamples = 0
    }
}
