package com.nuvio.app.features.details

internal data class OfflineMetaValidators(
    val sourceUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

internal sealed interface OfflineMetaFetchResult {
    data class Updated(
        val meta: MetaDetails,
        val sourceUrl: String?,
        val etag: String?,
        val lastModified: String?,
    ) : OfflineMetaFetchResult

    data class NotModified(
        val sourceUrl: String,
        val etag: String?,
        val lastModified: String?,
        val enrichedMeta: MetaDetails? = null,
    ) : OfflineMetaFetchResult

    data object Failed : OfflineMetaFetchResult
}
