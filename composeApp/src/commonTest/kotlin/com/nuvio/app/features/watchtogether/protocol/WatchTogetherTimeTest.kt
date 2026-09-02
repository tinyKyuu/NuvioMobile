package com.nuvio.app.features.watchtogether.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherTimeTest {
    @Test
    fun `canonical playback advances only while playing`() {
        val paused = WatchTogetherPlaybackAnchor(
            mode = WatchTogetherPlaybackMode.Paused,
            anchorPositionMs = 5_000L,
            anchorRelayTimeMs = 10_000L,
            rate = 1,
        )
        val playing = paused.copy(mode = WatchTogetherPlaybackMode.Playing)

        assertEquals(5_000L, WatchTogetherCanonicalClock.positionAt(paused, 12_500L))
        assertEquals(7_500L, WatchTogetherCanonicalClock.positionAt(playing, 12_500L))
        assertFailsWith<IllegalArgumentException> {
            WatchTogetherCanonicalClock.positionAt(playing, 9_999L)
        }
    }

    @Test
    fun `relay clock keeps the lowest latency sample and prefers the newest tie`() {
        val estimator = RelayClockEstimator(capacity = 3)
        estimator.record(
            RelayClockSample(
                localSentAtMs = 1_000L,
                localReceivedAtMs = 1_100L,
                relayTimeMs = 6_050L,
            )
        )
        estimator.record(
            RelayClockSample(
                localSentAtMs = 2_000L,
                localReceivedAtMs = 2_020L,
                relayTimeMs = 7_010L,
            )
        )
        val best = estimator.record(
            RelayClockSample(
                localSentAtMs = 3_000L,
                localReceivedAtMs = 3_020L,
                relayTimeMs = 8_015L,
            )
        )

        assertEquals(5_005L, best.offsetMs)
        assertEquals(20L, best.roundTripMs)
        assertEquals(3, best.sampleCount)
        assertEquals(9_005L, estimator.relayTimeAt(4_000L))

        estimator.reset()
        assertNull(estimator.relayTimeAt(4_000L))
    }

    @Test
    fun `source offset translates canonical time without entering room state`() {
        val local = SourceTimeMapper.localPosition(
            canonicalPositionMs = 10_000L,
            sourceOffsetMs = 2_500L,
        )
        val canonical = SourceTimeMapper.canonicalPosition(
            localPositionMs = local.positionMs,
            sourceOffsetMs = 2_500L,
        )

        assertEquals(12_500L, local.positionMs)
        assertFalse(local.clamped)
        assertEquals(10_000L, canonical.positionMs)
        assertFalse(canonical.clamped)

        val clamped = SourceTimeMapper.localPosition(
            canonicalPositionMs = 1_000L,
            sourceOffsetMs = -2_000L,
        )
        assertEquals(0L, clamped.positionMs)
        assertTrue(clamped.clamped)
    }
}
