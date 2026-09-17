package com.nuvio.app.features.downloads

internal enum class DownloadEntrySource {
    Library,
    Settings,
    Player,
    DeepLink,
    LegacyShowRoute,
}

internal enum class DownloadNavigationTarget {
    CompletedLibrary,
    Activity,
    Policy,
}

internal fun resolveDownloadNavigationTarget(
    source: DownloadEntrySource,
    explicitDestination: String? = null,
): DownloadNavigationTarget {
    return when (explicitDestination?.trim()?.lowercase()) {
        "activity" -> DownloadNavigationTarget.Activity
        "completed", "library", "legacy" -> DownloadNavigationTarget.CompletedLibrary
        "policy", "settings" -> DownloadNavigationTarget.Policy
        else -> when (source) {
            DownloadEntrySource.Settings -> DownloadNavigationTarget.Policy
            DownloadEntrySource.LegacyShowRoute -> DownloadNavigationTarget.CompletedLibrary
            DownloadEntrySource.Library,
            DownloadEntrySource.Player,
            DownloadEntrySource.DeepLink,
            -> DownloadNavigationTarget.Activity
        }
    }
}
