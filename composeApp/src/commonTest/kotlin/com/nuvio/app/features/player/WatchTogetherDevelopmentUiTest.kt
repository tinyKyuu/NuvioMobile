package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTogetherDevelopmentUiTest {
    @Test
    fun formatsRoomCodesForReadingAndSharing() {
        assertEquals("ABCD-EFGH", formatRoomCode("abcd-efgh"))
        assertEquals("ABCD", formatRoomCode("abcd"))
    }

    @Test
    fun roomCodeInputKeepsOnlyEightUnambiguousCharacters() {
        assertEquals("A2B3C4D5", normalizeRoomCode("a2-b3 c4.d5 extra"))
        assertEquals("ABCDEFGH", normalizeRoomCode("ABCD-1I0O-EFGH"))
    }
}
