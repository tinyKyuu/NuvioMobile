package com.nuvio.app.features.watchtogether.hosted

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface WatchTogetherCredentialStore {
    fun load(key: String): String?

    fun save(key: String, value: String)

    fun delete(key: String)
}

internal expect object WatchTogetherPlatformSecurity : WatchTogetherCredentialStore {
    fun secureRandomBytes(size: Int): ByteArray

    fun nowEpochMs(): Long
}

internal class WatchTogetherSecureSessionManager(
    private val key: String,
    private val store: WatchTogetherCredentialStore = WatchTogetherPlatformSecurity,
) : SessionManager {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun saveSession(session: UserSession) {
        store.save(key, json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession? {
        val payload = store.load(key) ?: return null
        return try {
            json.decodeFromString<UserSession>(payload)
        } catch (_: SerializationException) {
            store.delete(key)
            null
        } catch (_: IllegalArgumentException) {
            store.delete(key)
            null
        }
    }

    override suspend fun deleteSession() {
        store.delete(key)
    }
}

internal object WatchTogetherInviteSecretGenerator {
    private const val SECRET_LENGTH = 43
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun generate(): String = buildString(SECRET_LENGTH) {
        WatchTogetherPlatformSecurity.secureRandomBytes(SECRET_LENGTH).forEach { byte ->
            append(ALPHABET[byte.toInt() and 0x3f])
        }
    }
}
