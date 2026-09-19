package com.nuvio.app.features.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TrackingAuthProfileSessionTest {
    @Test
    fun `suspended authorization exchange cannot cross profiles`() = runBlocking {
        val guard = TrackingAuthProfileSessionGuard(initialProfileId = 1)
        val session = guard.capture()
        val exchangeStarted = CompletableDeferred<Unit>()
        val exchangeResult = CompletableDeferred<String>()
        val credentials = mutableMapOf<Int, String>()
        val revokedTokens = mutableListOf<String>()
        val pendingState = mutableMapOf(1 to "profile-one-pending", 2 to "profile-two-pending")
        var visibleUiProfile = 1
        var visibleUiConnected = false

        val exchange = launch {
            exchangeStarted.complete(Unit)
            val token = exchangeResult.await()
            if (guard.isCurrent(session)) {
                credentials[session.profileId] = token
                pendingState.remove(session.profileId)
                visibleUiProfile = session.profileId
                visibleUiConnected = true
            } else {
                revokedTokens += token
            }
        }

        exchangeStarted.await()
        guard.moveTo(profileId = 2)
        visibleUiProfile = 2
        exchangeResult.complete("late-profile-one-token")
        exchange.join()

        assertNull(credentials[1])
        assertNull(credentials[2])
        assertEquals("profile-one-pending", pendingState[1])
        assertEquals("profile-two-pending", pendingState[2])
        assertEquals(listOf("late-profile-one-token"), revokedTokens)
        assertEquals(2, visibleUiProfile)
        assertFalse(visibleUiConnected)
    }

    @Test
    fun `suspended token refresh cannot replace another profile credentials or UI`() = runBlocking {
        val guard = TrackingAuthProfileSessionGuard(initialProfileId = 1)
        val session = guard.capture()
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshResult = CompletableDeferred<String>()
        val credentials = mutableMapOf(1 to "profile-one-token", 2 to "profile-two-token")
        val revokedTokens = mutableListOf<String>()
        var visibleUiProfile = 1
        var visibleUiToken = credentials.getValue(1)

        val refresh = launch {
            refreshStarted.complete(Unit)
            val token = refreshResult.await()
            if (guard.isCurrent(session)) {
                credentials[session.profileId] = token
                visibleUiProfile = session.profileId
                visibleUiToken = token
            } else {
                revokedTokens += token
            }
        }

        refreshStarted.await()
        guard.moveTo(profileId = 2)
        visibleUiProfile = 2
        visibleUiToken = credentials.getValue(2)
        refreshResult.complete("late-profile-one-refresh")
        refresh.join()

        assertEquals("profile-one-token", credentials[1])
        assertEquals("profile-two-token", credentials[2])
        assertEquals(listOf("late-profile-one-refresh"), revokedTokens)
        assertEquals(2, visibleUiProfile)
        assertEquals("profile-two-token", visibleUiToken)
    }
}
