package com.nuvio.app.features.details

internal data class MetaDetailsDisplayPolicy(
    val displayedMeta: MetaDetails?,
    val ordinaryOnlineRequestsAllowed: Boolean,
    val deferredOnlineWorkAllowed: Boolean,
)

internal fun resolveMetaDetailsDisplayPolicy(
    repositoryMeta: MetaDetails?,
    offlineMeta: MetaDetails?,
    cachedMeta: MetaDetails?,
    isOfflineLike: Boolean,
): MetaDetailsDisplayPolicy {
    val displayedMeta = if (isOfflineLike) {
        offlineMeta ?: repositoryMeta ?: cachedMeta
    } else {
        repositoryMeta ?: offlineMeta ?: cachedMeta
    }
    return MetaDetailsDisplayPolicy(
        displayedMeta = displayedMeta,
        ordinaryOnlineRequestsAllowed = !isOfflineLike,
        deferredOnlineWorkAllowed = !isOfflineLike && displayedMeta != null,
    )
}

internal fun shouldScheduleInitialMetaLoad(
    policy: MetaDetailsDisplayPolicy,
    isLoading: Boolean,
    autoLoadAttempted: Boolean,
): Boolean = policy.ordinaryOnlineRequestsAllowed &&
    !autoLoadAttempted &&
    policy.displayedMeta == null &&
    !isLoading

internal fun shouldScheduleMetaEnrichment(
    policy: MetaDetailsDisplayPolicy,
    isLoading: Boolean,
    attemptedFingerprint: String?,
    currentFingerprint: String,
): Boolean = policy.ordinaryOnlineRequestsAllowed &&
    policy.displayedMeta != null &&
    !isLoading &&
    attemptedFingerprint != currentFingerprint

internal fun offlineEpisodeRequiresInternet(
    meta: MetaDetails,
    isOfflineLike: Boolean,
    isDownloaded: Boolean,
): Boolean = isOfflineLike && meta.isOfflineSnapshot && !isDownloaded
