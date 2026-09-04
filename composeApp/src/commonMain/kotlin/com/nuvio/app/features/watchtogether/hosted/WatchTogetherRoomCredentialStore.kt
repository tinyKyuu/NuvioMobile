package com.nuvio.app.features.watchtogether.hosted

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class WatchTogetherStoredRoomCredential(
    val roomId: String,
    val roomCode: String,
    val participantId: String,
    val sessionId: String,
    val isHost: Boolean,
    val displayName: String,
    val invitationSecret: String,
    val expiresAtMs: Long,
)

internal interface WatchTogetherRoomCredentialStore {
    fun load(serviceId: String): WatchTogetherStoredRoomCredential?

    fun save(serviceId: String, credential: WatchTogetherStoredRoomCredential)

    fun delete(serviceId: String)
}

internal class WatchTogetherSecureRoomCredentialStore(
    private val store: WatchTogetherCredentialStore = WatchTogetherPlatformSecurity,
) : WatchTogetherRoomCredentialStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun load(serviceId: String): WatchTogetherStoredRoomCredential? {
        val key = storageKey(serviceId)
        val payload = store.load(key) ?: return null
        return try {
            json.decodeFromString<WatchTogetherStoredRoomCredential>(payload)
        } catch (_: SerializationException) {
            store.delete(key)
            null
        } catch (_: IllegalArgumentException) {
            store.delete(key)
            null
        }
    }

    override fun save(serviceId: String, credential: WatchTogetherStoredRoomCredential) {
        store.save(storageKey(serviceId), json.encodeToString(credential))
    }

    override fun delete(serviceId: String) {
        store.delete(storageKey(serviceId))
    }

    private fun storageKey(serviceId: String): String =
        "watch_together_room_credential_v1.$serviceId"
}
