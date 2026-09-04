package com.nuvio.app.features.watchtogether.hosted

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherSecureSessionManagerTest {
    @Test
    fun `round trips the service session through the isolated credential key`() = runBlocking {
        val store = FakeCredentialStore()
        val manager = WatchTogetherSecureSessionManager("service-a", store)
        val session = UserSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresIn = 3_600L,
            tokenType = "bearer",
        )

        manager.saveSession(session)

        assertEquals(session, manager.loadSession())
        assertEquals(setOf("service-a"), store.values.keys)
    }

    @Test
    fun `deletes malformed stored sessions instead of retrying them`() = runBlocking {
        val store = FakeCredentialStore().apply { values["service-a"] = "not-json" }
        val manager = WatchTogetherSecureSessionManager("service-a", store)

        assertNull(manager.loadSession())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `generates url safe high entropy invitation secrets`() {
        val first = WatchTogetherInviteSecretGenerator.generate()
        val second = WatchTogetherInviteSecretGenerator.generate()

        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertNotEquals(first, second)
    }

    @Test
    fun `room reconnect credentials are isolated by service and malformed data is removed`() {
        val backingStore = FakeCredentialStore()
        val roomStore = WatchTogetherSecureRoomCredentialStore(backingStore)
        val credential = WatchTogetherStoredRoomCredential(
            roomId = "00000000-0000-4000-8000-000000000001",
            roomCode = "ABCD1234",
            participantId = "00000000-0000-4000-8000-000000000002",
            sessionId = "00000000-0000-4000-8000-000000000003",
            isHost = false,
            displayName = "Pilot Guest",
            invitationSecret = "0123456789_abcdefghijklmnopqrstuvwxyz-ABCDE",
            expiresAtMs = 4_000_000_000_000L,
        )

        roomStore.save("service-a", credential)

        assertEquals(credential, roomStore.load("service-a"))
        assertNull(roomStore.load("service-b"))
        backingStore.values["watch_together_room_credential_v1.service-b"] = "not-json"
        assertNull(roomStore.load("service-b"))
        assertTrue("watch_together_room_credential_v1.service-b" !in backingStore.values)
    }

    @Test
    fun `installed service manifest is persisted separately from service credentials`() {
        val backingStore = FakeCredentialStore()
        val configurationStore = WatchTogetherSecureServiceConfigurationStore(backingStore)

        configurationStore.saveManifestUrl("  https://watch.example.test/manifest.json  ")

        assertEquals(
            "https://watch.example.test/manifest.json",
            configurationStore.loadManifestUrl(),
        )
        assertEquals(
            setOf("watch_together_service_manifest_url_v1"),
            backingStore.values.keys,
        )

        configurationStore.deleteManifestUrl()
        assertNull(configurationStore.loadManifestUrl())
    }
}

private class FakeCredentialStore : WatchTogetherCredentialStore {
    val values = mutableMapOf<String, String>()

    override fun load(key: String): String? = values[key]

    override fun save(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
