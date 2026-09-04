package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServiceManifest
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServiceTransport
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherContractValidator
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherTransportProfile
import io.ktor.http.Url
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

internal fun createSupabaseDirectWatchTogetherTransport(
    manifest: WatchTogetherServiceManifest,
    transport: WatchTogetherServiceTransport,
): WatchTogetherHostedTransport {
    val validation = WatchTogetherContractValidator.validate(manifest)
    require(validation.isValid) {
        "Invalid Watch Together service manifest: ${validation.issues.first().path} " +
            validation.issues.first().message
    }
    require(transport in manifest.transports) { "Transport is not declared by the service manifest" }
    require(transport.profile == WatchTogetherTransportProfile.SupabaseDirectV1) {
        "Unsupported Watch Together transport profile"
    }
    val client = createSupabaseClient(
        supabaseUrl = transport.projectUrl,
        supabaseKey = transport.publishableKey,
    ) {
        install(Auth) {
            sessionManager = WatchTogetherSecureSessionManager(
                key = watchTogetherSessionKey(manifest.id, transport.projectUrl),
            )
        }
        install(Postgrest)
        install(Realtime)
    }
    return SupabaseDirectWatchTogetherTransport(SupabaseKtWatchTogetherBackend(client))
}

private fun watchTogetherSessionKey(serviceId: String, projectUrl: String): String {
    val origin = Url(projectUrl).let { url ->
        "${url.protocol.name}.${url.host}.${url.port}"
    }
    return "watch_together_service_session_v1.$serviceId.$origin"
}

private class SupabaseKtWatchTogetherBackend(
    private val client: SupabaseClient,
) : WatchTogetherSupabaseBackend {
    override suspend fun signInAnonymously() {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) {
            client.auth.signInAnonymously()
        }
    }

    override suspend fun requestHostEmailOtp(email: String) {
        client.auth.awaitInitialization()
        client.auth.signInWith(OTP, redirectUrl = null) {
            this.email = email
            createUser = true
        }
    }

    override suspend fun verifyHostEmailOtp(email: String, token: String) {
        client.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = email,
            token = token,
        )
    }

    override suspend fun invoke(function: String, parameters: JsonObject): String =
        client.postgrest.rpc(function, parameters).data

    override suspend fun subscribePrivateRoom(
        topic: String,
        event: String,
    ): WatchTogetherSupabaseSubscription {
        client.auth.awaitInitialization()
        require(client.auth.currentSessionOrNull() != null) {
            "A Watch Together service session is required"
        }
        client.realtime.setAuth()
        val channel = client.channel(topic) { isPrivate = true }
        val payloads = channel.broadcastFlow<JsonObject>(event).map(JsonObject::toString)
        channel.subscribe(blockUntilSubscribed = true)
        return SupabaseKtRoomSubscription(client, channel, payloads)
    }

    override suspend fun clearSession() {
        client.realtime.removeAllChannels()
        try {
            if (client.auth.currentSessionOrNull() != null) {
                client.auth.signOut()
            }
        } finally {
            client.auth.clearSession()
        }
    }

    override suspend fun close() {
        client.close()
    }
}

private class SupabaseKtRoomSubscription(
    private val client: SupabaseClient,
    private val channel: RealtimeChannel,
    override val payloads: Flow<String>,
) : WatchTogetherSupabaseSubscription {
    private var closed = false

    override suspend fun close() {
        if (closed) return
        closed = true
        client.realtime.removeChannel(channel)
    }
}
