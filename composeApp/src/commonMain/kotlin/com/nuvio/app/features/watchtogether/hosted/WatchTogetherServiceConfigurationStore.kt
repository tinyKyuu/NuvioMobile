package com.nuvio.app.features.watchtogether.hosted

internal interface WatchTogetherServiceConfigurationStore {
    fun loadManifestUrl(): String?

    fun saveManifestUrl(manifestUrl: String)

    fun deleteManifestUrl()
}

internal class WatchTogetherSecureServiceConfigurationStore(
    private val store: WatchTogetherCredentialStore = WatchTogetherPlatformSecurity,
) : WatchTogetherServiceConfigurationStore {
    override fun loadManifestUrl(): String? =
        store.load(STORAGE_KEY)?.trim()?.takeIf(String::isNotEmpty)

    override fun saveManifestUrl(manifestUrl: String) {
        store.save(STORAGE_KEY, manifestUrl.trim())
    }

    override fun deleteManifestUrl() {
        store.delete(STORAGE_KEY)
    }

    private companion object {
        const val STORAGE_KEY = "watch_together_service_manifest_url_v1"
    }
}
