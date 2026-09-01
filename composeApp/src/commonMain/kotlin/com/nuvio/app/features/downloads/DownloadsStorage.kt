package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadLegacyPayload(profileId: Int): String?
    fun removeLegacyPayload(profileId: Int)
}
