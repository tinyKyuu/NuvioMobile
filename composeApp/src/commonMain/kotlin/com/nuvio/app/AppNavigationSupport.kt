package com.nuvio.app

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.collection.CollectionEditorPage
import com.nuvio.app.features.collection.CollectionEditorRepository
import com.nuvio.app.features.collection.FolderDetailRepository
import com.nuvio.app.features.collection.disposeCollectionEditorPage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.player.ExternalPlayerPlaybackRequest
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

internal val navigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(TabsRoute::class, TabsRoute.serializer())
            subclass(DetailRoute::class, DetailRoute.serializer())
            subclass(PersonDetailRoute::class, PersonDetailRoute.serializer())
            subclass(EntityBrowseRoute::class, EntityBrowseRoute.serializer())
            subclass(SettingsPageRoute::class, SettingsPageRoute.serializer())
            subclass(HomescreenSettingsRoute::class, HomescreenSettingsRoute.serializer())
            subclass(MetaScreenSettingsRoute::class, MetaScreenSettingsRoute.serializer())
            subclass(ContinueWatchingSettingsRoute::class, ContinueWatchingSettingsRoute.serializer())
            subclass(DownloadsSettingsRoute::class, DownloadsSettingsRoute.serializer())
            subclass(DownloadActivityRoute::class, DownloadActivityRoute.serializer())
            subclass(DownloadShowRoute::class, DownloadShowRoute.serializer())
            subclass(AddonsSettingsRoute::class, AddonsSettingsRoute.serializer())
            subclass(PluginsSettingsRoute::class, PluginsSettingsRoute.serializer())
            subclass(AccountSettingsRoute::class, AccountSettingsRoute.serializer())
            subclass(SupportersContributorsSettingsRoute::class, SupportersContributorsSettingsRoute.serializer())
            subclass(LicensesAttributionsSettingsRoute::class, LicensesAttributionsSettingsRoute.serializer())
            subclass(CollectionsRoute::class, CollectionsRoute.serializer())
            subclass(CollectionEditorRoute::class, CollectionEditorRoute.serializer())
            subclass(CollectionEditorPageRoute::class, CollectionEditorPageRoute.serializer())
            subclass(FolderDetailRoute::class, FolderDetailRoute.serializer())
            subclass(StreamRoute::class, StreamRoute.serializer())
            subclass(CatalogRoute::class, CatalogRoute.serializer())
            subclass(PlayerRoute::class, PlayerRoute.serializer())
        }
    }
}

internal fun disposeRouteResources(route: AppRoute) {
    when (route) {
        is StreamRoute -> {
            StreamsRepository.clear()
            StreamLaunchStore.remove(route.launchId)
        }

        is PlayerRoute -> {
            ResumePromptRepository.markPlayerExitedNormally()
            PlayerLaunchStore.remove(route.launchId)
        }

        is CatalogRoute -> {
            CatalogRepository.clear()
            CatalogLaunchStore.remove(route.launchId)
        }

        is CollectionEditorRoute -> CollectionEditorRepository.clear()
        is CollectionEditorPageRoute -> {
            runCatching { CollectionEditorPage.valueOf(route.pageName) }
                .getOrNull()
                ?.let(::disposeCollectionEditorPage)
        }
        is FolderDetailRoute -> FolderDetailRepository.clear()
        else -> Unit
    }
}

internal data class PosterActionTarget(
    val preview: MetaPreview,
    val libraryItem: LibraryItem? = null,
    val libraryDisplaySectionKey: String? = null,
    val libraryMembershipListKey: String? = null,
)

internal fun libraryPosterActionTarget(
    item: LibraryItem,
    displaySectionKey: String,
    membershipListKey: String? = null,
): PosterActionTarget = PosterActionTarget(
    preview = item.toMetaPreview(),
    libraryItem = item,
    libraryDisplaySectionKey = displaySectionKey,
    libraryMembershipListKey = membershipListKey,
)

internal fun libraryMembershipListKey(
    sourceMode: LibrarySourceMode,
    displaySectionKey: String,
    providerSectionKeys: Set<String>,
): String? = displaySectionKey.takeIf {
    sourceMode != LibrarySourceMode.LOCAL && it in providerSectionKeys
}

internal enum class PosterLibraryMembershipAction {
    AddLocal,
    AddRemote,
    RemoveLocal,
    RemoveRemoteList,
    RemoveRemoteSource,
}

internal fun posterLibraryMembershipAction(
    isSaved: Boolean,
    isRemoteLibrarySource: Boolean,
    membershipListKey: String?,
): PosterLibraryMembershipAction = when {
    !isSaved && isRemoteLibrarySource -> PosterLibraryMembershipAction.AddRemote
    !isSaved -> PosterLibraryMembershipAction.AddLocal
    !isRemoteLibrarySource -> PosterLibraryMembershipAction.RemoveLocal
    !membershipListKey.isNullOrBlank() -> PosterLibraryMembershipAction.RemoveRemoteList
    else -> PosterLibraryMembershipAction.RemoveRemoteSource
}

internal fun PlayerLaunch.toExternalPlayerPlaybackRequest(): ExternalPlayerPlaybackRequest =
    ExternalPlayerPlaybackRequest(
        sourceUrl = sourceUrl,
        title = title,
        streamTitle = streamTitle,
        sourceHeaders = sourceHeaders,
        resumePositionMs = initialPositionMs,
        season = seasonNumber,
        episode = episodeNumber,
        episodeTitle = episodeTitle,
    )
