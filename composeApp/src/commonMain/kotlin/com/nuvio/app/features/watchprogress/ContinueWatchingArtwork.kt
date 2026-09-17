package com.nuvio.app.features.watchprogress

data class ContinueWatchingArtworkSet(
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val episodeThumbnail: String? = null,
)

internal data class ContinueWatchingArtworkResolution(
    val local: ContinueWatchingArtworkSet = ContinueWatchingArtworkSet(),
    val remote: ContinueWatchingArtworkSet = ContinueWatchingArtworkSet(),
)

internal enum class ContinueWatchingArtworkSourceCategory {
    CurrentLocal,
    Remote,
}

internal enum class ContinueWatchingArtworkRole {
    EpisodeThumbnail,
    Poster,
    Background,
    Image,
}

internal enum class ContinueWatchingArtworkPresentation {
    Default,
    LandscapeCard,
    Poster,
}

internal data class ContinueWatchingArtworkCandidate(
    val url: String,
    val sourceCategory: ContinueWatchingArtworkSourceCategory,
    val role: ContinueWatchingArtworkRole,
)

internal fun String?.durableArtworkUrlOrNull(): String? {
    val normalized = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    return normalized.takeUnless { value ->
        value.startsWith("file:", ignoreCase = true) ||
            value.startsWith("content:", ignoreCase = true)
    }
}

internal fun ContinueWatchingItem.withResolvedArtwork(
    resolution: ContinueWatchingArtworkResolution?,
    allowRemote: Boolean,
): ContinueWatchingItem {
    val remote = resolution?.remote
    return copy(
        imageUrl = if (allowRemote) imageUrl.durableArtworkUrlOrNull() else null,
        logo = if (allowRemote) {
            logo.durableArtworkUrlOrNull() ?: remote?.logo.durableArtworkUrlOrNull()
        } else {
            null
        },
        poster = if (allowRemote) {
            poster.durableArtworkUrlOrNull() ?: remote?.poster.durableArtworkUrlOrNull()
        } else {
            null
        },
        background = if (allowRemote) {
            background.durableArtworkUrlOrNull() ?: remote?.background.durableArtworkUrlOrNull()
        } else {
            null
        },
        episodeThumbnail = if (allowRemote) {
            episodeThumbnail.durableArtworkUrlOrNull()
                ?: remote?.episodeThumbnail.durableArtworkUrlOrNull()
        } else {
            null
        },
        localArtwork = resolution?.local,
    )
}

internal fun ContinueWatchingItem.artworkCandidates(
    presentation: ContinueWatchingArtworkPresentation,
    useEpisodeThumbnails: Boolean,
    preferBackdropForNextUp: Boolean = false,
): List<ContinueWatchingArtworkCandidate> {
    val order = when (presentation) {
        ContinueWatchingArtworkPresentation.Poster -> {
            if (seasonNumber == null || episodeNumber == null) {
                defaultArtworkRoleOrder(useEpisodeThumbnails)
            } else {
                listOfNotNull(
                    ContinueWatchingArtworkRole.Poster,
                    ContinueWatchingArtworkRole.Background,
                    ContinueWatchingArtworkRole.Image,
                    ContinueWatchingArtworkRole.EpisodeThumbnail.takeIf { useEpisodeThumbnails },
                )
            }
        }
        ContinueWatchingArtworkPresentation.LandscapeCard -> when {
            isNextUp && preferBackdropForNextUp -> listOf(
                ContinueWatchingArtworkRole.Background,
                ContinueWatchingArtworkRole.Poster,
                ContinueWatchingArtworkRole.EpisodeThumbnail,
                ContinueWatchingArtworkRole.Image,
            )
            isNextUp && useEpisodeThumbnails -> listOf(
                ContinueWatchingArtworkRole.EpisodeThumbnail,
                ContinueWatchingArtworkRole.Background,
                ContinueWatchingArtworkRole.Poster,
                ContinueWatchingArtworkRole.Image,
            )
            useEpisodeThumbnails -> listOf(
                ContinueWatchingArtworkRole.EpisodeThumbnail,
                ContinueWatchingArtworkRole.Background,
                ContinueWatchingArtworkRole.Poster,
                ContinueWatchingArtworkRole.Image,
            )
            else -> listOf(
                ContinueWatchingArtworkRole.Background,
                ContinueWatchingArtworkRole.Poster,
                ContinueWatchingArtworkRole.EpisodeThumbnail,
                ContinueWatchingArtworkRole.Image,
            )
        }
        ContinueWatchingArtworkPresentation.Default -> defaultArtworkRoleOrder(useEpisodeThumbnails)
    }

    return buildList {
        order.forEach { role ->
            val localUrl = when (role) {
                ContinueWatchingArtworkRole.EpisodeThumbnail -> localArtwork?.episodeThumbnail
                ContinueWatchingArtworkRole.Poster -> localArtwork?.poster
                ContinueWatchingArtworkRole.Background -> localArtwork?.background
                ContinueWatchingArtworkRole.Image -> null
            }?.trim()?.takeIf(String::isNotBlank)
            if (localUrl != null) {
                add(
                    ContinueWatchingArtworkCandidate(
                        url = localUrl,
                        sourceCategory = ContinueWatchingArtworkSourceCategory.CurrentLocal,
                        role = role,
                    ),
                )
            }

            val remoteUrl = when (role) {
                ContinueWatchingArtworkRole.EpisodeThumbnail -> episodeThumbnail
                ContinueWatchingArtworkRole.Poster -> poster
                ContinueWatchingArtworkRole.Background -> background
                ContinueWatchingArtworkRole.Image -> imageUrl.takeUnless {
                    presentation == ContinueWatchingArtworkPresentation.Poster &&
                        seasonNumber != null &&
                        episodeNumber != null &&
                        it?.trim() == episodeThumbnail?.trim()
                }
            }.durableArtworkUrlOrNull()
            if (remoteUrl != null) {
                add(
                    ContinueWatchingArtworkCandidate(
                        url = remoteUrl,
                        sourceCategory = ContinueWatchingArtworkSourceCategory.Remote,
                        role = role,
                    ),
                )
            }
        }
    }.distinctBy { candidate -> candidate.url }
}

internal fun nextContinueWatchingArtworkCandidateIndex(
    currentIndex: Int,
    candidateCount: Int,
): Int? = (currentIndex + 1).takeIf { next -> next in 0 until candidateCount }

internal fun WatchProgressEntry.withDurableArtwork(
    resolution: ContinueWatchingArtworkResolution?,
    previous: WatchProgressEntry?,
): WatchProgressEntry {
    fun select(
        current: String?,
        recovered: String?,
        fallback: String?,
    ): String? = current.durableArtworkUrlOrNull()
        ?: recovered.durableArtworkUrlOrNull()
        ?: fallback.durableArtworkUrlOrNull()

    return copy(
        logo = select(logo, resolution?.remote?.logo, previous?.logo),
        poster = select(poster, resolution?.remote?.poster, previous?.poster),
        background = select(background, resolution?.remote?.background, previous?.background),
        episodeThumbnail = select(
            episodeThumbnail,
            resolution?.remote?.episodeThumbnail,
            previous?.episodeThumbnail,
        ),
    )
}

private fun ContinueWatchingItem.defaultArtworkRoleOrder(
    useEpisodeThumbnails: Boolean,
): List<ContinueWatchingArtworkRole> = when {
    isNextUp && useEpisodeThumbnails -> listOf(
        ContinueWatchingArtworkRole.EpisodeThumbnail,
        ContinueWatchingArtworkRole.Poster,
        ContinueWatchingArtworkRole.Background,
        ContinueWatchingArtworkRole.Image,
    )
    useEpisodeThumbnails -> listOf(
        ContinueWatchingArtworkRole.EpisodeThumbnail,
        ContinueWatchingArtworkRole.Poster,
        ContinueWatchingArtworkRole.Background,
        ContinueWatchingArtworkRole.Image,
    )
    else -> listOf(
        ContinueWatchingArtworkRole.Poster,
        ContinueWatchingArtworkRole.Background,
        ContinueWatchingArtworkRole.EpisodeThumbnail,
        ContinueWatchingArtworkRole.Image,
    )
}
