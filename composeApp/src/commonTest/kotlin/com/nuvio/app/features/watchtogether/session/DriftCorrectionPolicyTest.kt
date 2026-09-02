package com.nuvio.app.features.watchtogether.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DriftCorrectionPolicyTest {
    @Test
    fun ignoresSubThresholdDriftAndHardSeeksLargeDrift() {
        val policy = DriftCorrectionPolicy()

        assertEquals(DriftCorrectionDecision.None, policy.evaluate(250L, 1_000L))
        assertEquals(DriftCorrectionDecision.HardSeek, policy.evaluate(1_501L, 2_000L))
        assertEquals(DriftCorrectionDecision.HardSeek, policy.evaluate(-1_501L, 3_000L))
    }

    @Test
    fun requiresPersistentModerateDriftAndCorrectsOnlyTheOffTargetClient() {
        val policy = DriftCorrectionPolicy()

        assertEquals(DriftCorrectionDecision.None, policy.evaluate(800L, 1_000L))
        val ahead = assertIs<DriftCorrectionDecision.TemporaryRate>(policy.evaluate(810L, 2_000L))
        assertEquals(0.97, ahead.rate)
        assertEquals(6_000L, ahead.durationMs)

        assertEquals(DriftCorrectionDecision.None, policy.evaluate(-800L, 3_000L))
        assertEquals(DriftCorrectionDecision.None, policy.evaluate(-810L, 8_999L))
        assertEquals(DriftCorrectionDecision.None, policy.evaluate(-820L, 9_000L))
        val behind = assertIs<DriftCorrectionDecision.TemporaryRate>(policy.evaluate(-830L, 10_000L))
        assertEquals(1.03, behind.rate)
    }

    @Test
    fun aDirectionChangeRestartsPersistence() {
        val policy = DriftCorrectionPolicy()

        assertEquals(DriftCorrectionDecision.None, policy.evaluate(700L, 1_000L))
        assertEquals(DriftCorrectionDecision.None, policy.evaluate(-700L, 2_000L))
        assertIs<DriftCorrectionDecision.TemporaryRate>(policy.evaluate(-710L, 3_000L))
    }
}
