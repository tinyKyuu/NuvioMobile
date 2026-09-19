package com.nuvio.app.features.watchtogether.hosted

import com.nuvio.app.features.watchtogether.protocol.WatchTogetherManifestLoadFailure
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherManifestLoadResult
import com.nuvio.app.features.watchtogether.protocol.WatchTogetherServiceManifestLoader
import io.ktor.client.HttpClient

internal sealed interface WatchTogetherHostedServiceConnection {
    data class Connected(
        val serviceId: String,
        val serviceName: String,
        val transport: WatchTogetherHostedTransport,
    ) : WatchTogetherHostedServiceConnection

    data class Rejected(
        val reason: WatchTogetherManifestLoadFailure,
    ) : WatchTogetherHostedServiceConnection
}

internal interface WatchTogetherHostedServiceConnector {
    suspend fun connect(manifestUrl: String): WatchTogetherHostedServiceConnection

    fun close()
}

internal class ManifestWatchTogetherHostedServiceConnector(
    private val httpClient: HttpClient = HttpClient(),
) : WatchTogetherHostedServiceConnector {
    private val loader = WatchTogetherServiceManifestLoader(httpClient)

    override suspend fun connect(manifestUrl: String): WatchTogetherHostedServiceConnection =
        when (val result = loader.load(manifestUrl)) {
            is WatchTogetherManifestLoadResult.Compatible -> {
                WatchTogetherHostedServiceConnection.Connected(
                    serviceId = result.manifest.id,
                    serviceName = result.manifest.name,
                    transport = createSupabaseDirectWatchTogetherTransport(
                        manifest = result.manifest,
                        transport = result.transport,
                    ),
                )
            }

            is WatchTogetherManifestLoadResult.Rejected -> {
                WatchTogetherHostedServiceConnection.Rejected(result.reason)
            }
        }

    override fun close() {
        httpClient.close()
    }
}
