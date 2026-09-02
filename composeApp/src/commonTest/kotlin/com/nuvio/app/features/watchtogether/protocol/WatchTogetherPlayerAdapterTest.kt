package com.nuvio.app.features.watchtogether.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTogetherPlayerAdapterTest {
    @Test
    fun `every player command declares the capability it needs`() {
        assertEquals(
            WatchTogetherPlayerCapability.Pause,
            WatchTogetherPlayerCommand.Pause.requiredCapability(),
        )
        assertEquals(
            WatchTogetherPlayerCapability.Resume,
            WatchTogetherPlayerCommand.Resume.requiredCapability(),
        )
        assertEquals(
            WatchTogetherPlayerCapability.AbsoluteSeek,
            WatchTogetherPlayerCommand.SeekTo(10_000L).requiredCapability(),
        )
        assertEquals(
            WatchTogetherPlayerCapability.TemporaryRate,
            WatchTogetherPlayerCommand.SetTemporaryRate(
                rate = 1.02,
                durationMs = 2_000L,
            ).requiredCapability(),
        )
    }
}
