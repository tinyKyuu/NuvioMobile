package com.nuvio.app.features.library

import com.nuvio.app.features.watched.watchedItemKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDisplaySettingsTest {

    @Test
    fun `local default resolves to recently added while remote trackers preserve provider order`() {
        assertEquals(
            LibrarySortOption.ADDED_DESC,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.LOCAL),
        )
        assertEquals(
            LibrarySortOption.DEFAULT,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.TRAKT),
        )
        assertEquals(
            LibrarySortOption.DEFAULT,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.SIMKL),
        )

        val input = listOf(
            item("ranked-second", savedAt = 3L, traktRank = 2),
            item("unranked", savedAt = 4L),
            item("ranked-first", savedAt = 1L, traktRank = 1),
            item("ranked-first-newer", savedAt = 2L, traktRank = 1),
        )
        assertEquals(
            listOf("ranked-first-newer", "ranked-first", "ranked-second", "unranked"),
            sortLibraryItems(input, LibrarySortOption.DEFAULT, LibrarySourceMode.TRAKT).map { it.id },
        )
        assertEquals(
            listOf("unranked", "ranked-second", "ranked-first-newer", "ranked-first"),
            sortLibraryItems(input, LibrarySortOption.DEFAULT, LibrarySourceMode.LOCAL).map { it.id },
        )
    }

    @Test
    fun `added sorting works in both directions`() {
        val input = listOf(
            item("middle", savedAt = 2L),
            item("oldest", savedAt = 1L),
            item("newest", savedAt = 3L),
        )

        assertEquals(
            listOf("newest", "middle", "oldest"),
            sortLibraryItems(input, LibrarySortOption.ADDED_DESC, LibrarySourceMode.LOCAL).map { it.id },
        )
        assertEquals(
            listOf("oldest", "middle", "newest"),
            sortLibraryItems(input, LibrarySortOption.ADDED_ASC, LibrarySourceMode.TRAKT).map { it.id },
        )
    }

    @Test
    fun `title sorting ignores leading English articles`() {
        val input = listOf(
            item("batman", name = "The Batman"),
            item("arrival", name = "Arrival"),
            item("quiet", name = "A Quiet Place"),
        )

        assertEquals(
            listOf("arrival", "batman", "quiet"),
            sortLibraryItems(input, LibrarySortOption.TITLE_ASC, LibrarySourceMode.LOCAL).map { it.id },
        )
        assertEquals(
            listOf("quiet", "batman", "arrival"),
            sortLibraryItems(input, LibrarySortOption.TITLE_DESC, LibrarySourceMode.LOCAL).map { it.id },
        )
    }

    @Test
    fun `horizontal sections sort independently without changing section order`() {
        val sections = listOf(
            LibrarySection(
                type = "movie",
                displayTitle = "Movies",
                items = listOf(item("z", name = "Zulu"), item("a", name = "Alpha")),
            ),
            LibrarySection(
                type = "series",
                displayTitle = "Series",
                items = listOf(item("y", type = "series", name = "Yellow"), item("b", type = "series", name = "Beta")),
            ),
        )

        val sorted = sortLibrarySections(sections, LibrarySortOption.TITLE_ASC, LibrarySourceMode.LOCAL)

        assertEquals(listOf("movie", "series"), sorted.map { it.type })
        assertEquals(listOf("a", "z"), sorted[0].items.map { it.id })
        assertEquals(listOf("b", "y"), sorted[1].items.map { it.id })
    }

    @Test
    fun `vertical tracker projection selects one list then filters and sorts its items`() {
        val watchlist = LibrarySection(
            type = "watchlist",
            displayTitle = "Watchlist",
            items = listOf(
                item("z", name = "Zulu"),
                item("series", type = "series", name = "Series"),
                item("a", name = "Alpha"),
            ),
        )
        val personal = LibrarySection(
            type = "personal:1",
            displayTitle = "Favorites",
            items = listOf(item("favorite", name = "Favorite")),
        )

        val projection = buildLibraryVerticalProjection(
            sections = listOf(watchlist, personal),
            sourceMode = LibrarySourceMode.TRAKT,
            selectedSectionKey = "missing",
            selectedType = "movie",
            sortOption = LibrarySortOption.TITLE_ASC,
        )

        assertEquals("watchlist", projection.selectedSectionKey)
        assertEquals(listOf("movie", "series"), projection.availableTypes)
        assertEquals("movie", projection.selectedType)
        assertEquals(listOf("a", "z"), projection.entries.map { it.item.id })
        assertEquals(listOf("watchlist", "watchlist"), projection.entries.map { it.section.type })

        val simklProjection = buildLibraryVerticalProjection(
            sections = listOf(watchlist),
            sourceMode = LibrarySourceMode.SIMKL,
            selectedSectionKey = null,
            selectedType = null,
            sortOption = LibrarySortOption.DEFAULT,
        )
        assertEquals(listOf("watchlist"), simklProjection.availableSections.map { it.type })
        assertEquals("watchlist", simklProjection.selectedSectionKey)
    }

    @Test
    fun `downloaded projection combines all types in recent download order`() {
        val sections = listOf(
            LibrarySection(
                type = "movie",
                displayTitle = "Movies",
                items = listOf(item("movie", name = "Zulu", savedAt = 1L)),
            ),
            LibrarySection(
                type = "series",
                displayTitle = "Series",
                items = listOf(
                    item("series-z", type = "series", name = "Zulu", savedAt = 2L),
                    item("series-a", type = "series", name = "Alpha", savedAt = 3L),
                ),
            ),
        )

        val projection = buildLibraryVerticalProjection(
            sections = sections,
            sourceMode = LibrarySourceMode.LOCAL,
            selectedSectionKey = null,
            selectedType = null,
            sortOption = LibrarySortOption.ADDED_DESC,
        )

        assertEquals(emptyList(), projection.availableSections)
        assertEquals(listOf("movie", "series"), projection.availableTypes)
        assertEquals(null, projection.selectedType)
        assertEquals(listOf("series-a", "series-z", "movie"), projection.entries.map { it.item.id })
    }

    @Test
    fun `saved item reuses matching downloaded artwork without changing its identity`() {
        val saved = item("tt123", type = "show", name = "Saved").copy(
            poster = "https://images.test/remote-poster.jpg",
            banner = "https://images.test/remote-background.jpg",
        )
        val fallback = LibraryArtworkFallback(
            type = "series",
            ids = setOf("tt123", "tmdb:456"),
            poster = "file:///offline/poster.jpg",
            banner = "file:///offline/background.jpg",
            logo = "file:///offline/logo.png",
        )

        val resolved = saved.withArtworkFallback(listOf(fallback))

        assertEquals("tt123", resolved.id)
        assertEquals("show", resolved.type)
        assertEquals("file:///offline/poster.jpg", resolved.poster)
        assertEquals("file:///offline/background.jpg", resolved.banner)
        assertEquals("file:///offline/logo.png", resolved.logo)
        assertEquals(saved, saved.withArtworkFallback(listOf(fallback.copy(type = "movie"))))
    }

    @Test
    fun `artwork fallback keeps numeric provider ids in their namespaces`() {
        val tmdbItem = item("provider-item", name = "TMDB item").copy(
            poster = "https://images.test/remote.jpg",
            tmdbId = 42,
        )
        val unrelatedTraktFallback = LibraryArtworkFallback(
            type = "movie",
            ids = setOf("42", "trakt:42"),
            poster = "file:///offline/wrong.jpg",
        )

        assertEquals(tmdbItem, tmdbItem.withArtworkFallback(listOf(unrelatedTraktFallback)))
        assertEquals(
            "file:///offline/tmdb.jpg",
            tmdbItem.withArtworkFallback(
                listOf(
                    LibraryArtworkFallback(
                        type = "movie",
                        ids = setOf("tmdb:42"),
                        poster = "file:///offline/tmdb.jpg",
                    ),
                ),
            ).poster,
        )

        val traktItem = item("another-provider-item", name = "Trakt item").copy(traktId = 77)
        assertEquals(
            "file:///offline/trakt.jpg",
            traktItem.withArtworkFallback(
                listOf(
                    LibraryArtworkFallback(
                        type = "movie",
                        ids = setOf("trakt:77"),
                        poster = "file:///offline/trakt.jpg",
                    ),
                ),
            ).poster,
        )

        val exactItem = item("stable-id", name = "Exact item")
        assertEquals(
            "file:///offline/exact.jpg",
            exactItem.withArtworkFallback(
                listOf(
                    LibraryArtworkFallback(
                        type = "movie",
                        ids = setOf("stable-id"),
                        poster = "file:///offline/exact.jpg",
                    ),
                ),
            ).poster,
        )
    }

    @Test
    fun `display settings payload round trips and invalid values fall back safely`() {
        val state = LibraryDisplaySettingsUiState(
            layoutMode = LibraryLayoutMode.VERTICAL,
            sortOption = LibrarySortOption.TITLE_DESC,
            watchedFilter = LibraryWatchedFilter.UNWATCHED,
        )

        assertEquals(state, decodeLibraryDisplaySettings(encodeLibraryDisplaySettings(state)))
        assertEquals(
            LibraryDisplaySettingsUiState(),
            decodeLibraryDisplaySettings("""{"layout_mode":"unknown","sort_option":"unknown"}"""),
        )
    }

    @Test
    fun `All titles merges saved and downloaded aliases without duplicate posters`() {
        val saved = item("show-1", type = "show", name = "Saved title")
        val downloaded = item("show-1", type = "series", name = "Downloaded title").copy(
            poster = "file:///offline/poster.jpg",
        )

        val merged = mergeLibraryTitleItems(
            savedItems = listOf(saved),
            downloadedItems = listOf(downloaded, item("movie-2", name = "Download only")),
        )

        assertEquals(listOf("show-1", "movie-2"), merged.map { it.id })
        assertEquals("Saved title", merged.first().name)
        assertEquals("file:///offline/poster.jpg", merged.first().poster)
    }

    @Test
    fun `All titles combines local selected source and downloads without changing membership`() {
        val localOnly = item("local-only", name = "Local")
        val sharedLocal = item("shared", name = "Local metadata")
        val selectedOnly = item("selected-only", name = "Tracked")
        val sharedSelected = item("shared", name = "Tracked metadata")
        val downloadOnly = item("download-only", name = "Downloaded")
        val sharedDownload = item("shared", name = "Downloaded metadata").copy(
            poster = "file:///offline/shared.jpg",
        )

        val merged = mergeAllLibraryTitleItems(
            localItems = listOf(localOnly, sharedLocal),
            selectedSourceItems = listOf(selectedOnly, sharedSelected),
            downloadedItems = listOf(downloadOnly, sharedDownload),
        )

        assertEquals(listOf("local-only", "shared", "selected-only", "download-only"), merged.map { it.id })
        assertEquals("Tracked metadata", merged.first { it.id == "shared" }.name)
        assertEquals("file:///offline/shared.jpg", merged.first { it.id == "shared" }.poster)
        assertEquals(listOf("local-only", "shared"), listOf(localOnly, sharedLocal).map { it.id })
    }

    @Test
    fun `watched filter keeps fully watched series separate from partial progress`() {
        val watchedMovie = item("movie-watched")
        val unwatchedMovie = item("movie-unwatched")
        val completeSeries = item("series-complete", type = "series")
        val partialSeries = item("series-partial", type = "series")
        val sections = listOf(
            LibrarySection("movie", "Movies", listOf(watchedMovie, unwatchedMovie)),
            LibrarySection("series", "Series", listOf(completeSeries, partialSeries)),
        )
        val watchedKeys = watchedItemKeys(type = "movie", id = watchedMovie.id)
        val fullyWatchedSeriesKeys = watchedItemKeys(type = "series", id = completeSeries.id)

        assertEquals(
            listOf("movie-watched", "series-complete"),
            filterLibrarySectionsByWatchedState(
                sections = sections,
                filter = LibraryWatchedFilter.WATCHED,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            ).flatMap(LibrarySection::items).map { it.id },
        )
        assertEquals(
            listOf("movie-unwatched", "series-partial"),
            filterLibrarySectionsByWatchedState(
                sections = sections,
                filter = LibraryWatchedFilter.UNWATCHED,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            ).flatMap(LibrarySection::items).map { it.id },
        )
    }

    @Test
    fun `available genres are normalized deduplicated and sorted`() {
        val sections = listOf(
            LibrarySection(
                type = "movie",
                displayTitle = "Movies",
                items = listOf(
                    item("movie").copy(genres = listOf(" Drama ", "Action", "")),
                ),
            ),
            LibrarySection(
                type = "series",
                displayTitle = "Series",
                items = listOf(
                    item("series", type = "series").copy(genres = listOf("action", "Comedy")),
                ),
            ),
        )

        assertEquals(
            listOf(
                LibraryGenreOption(key = "action", label = "Action"),
                LibraryGenreOption(key = "comedy", label = "Comedy"),
                LibraryGenreOption(key = "drama", label = "Drama"),
            ),
            availableLibraryGenreOptions(sections),
        )
    }

    @Test
    fun `genre filter preserves section and item order while dropping empty sections`() {
        val sections = listOf(
            LibrarySection(
                type = "movie",
                displayTitle = "Movies",
                items = listOf(
                    item("movie-drama").copy(genres = listOf("Drama")),
                    item("movie-comedy").copy(genres = listOf("Comedy")),
                ),
            ),
            LibrarySection(
                type = "series",
                displayTitle = "Series",
                items = listOf(
                    item("series-drama", type = "series").copy(genres = listOf("Crime", "drama")),
                ),
            ),
            LibrarySection(
                type = "documentary",
                displayTitle = "Documentaries",
                items = listOf(item("documentary").copy(genres = listOf("Documentary"))),
            ),
        )

        val filtered = filterLibrarySectionsByGenre(sections, selectedGenreKey = "DRAMA")

        assertEquals(listOf("movie", "series"), filtered.map { section -> section.type })
        assertEquals(
            listOf("movie-drama", "series-drama"),
            filtered.flatMap(LibrarySection::items).map { item -> item.id },
        )
    }

    @Test
    fun `genre selection temporarily falls back when the current scope lacks it`() {
        val allTitleGenres = listOf(
            LibraryGenreOption(key = "action", label = "Action"),
            LibraryGenreOption(key = "drama", label = "Drama"),
        )
        val downloadedGenres = listOf(
            LibraryGenreOption(key = "action", label = "Action"),
        )

        assertEquals("drama", effectiveLibraryGenreSelection(" Drama ", allTitleGenres))
        assertEquals(null, effectiveLibraryGenreSelection("drama", downloadedGenres))
        assertEquals("drama", effectiveLibraryGenreSelection("drama", allTitleGenres))
    }

    private fun item(
        id: String,
        type: String = "movie",
        name: String = id,
        savedAt: Long = 0L,
        traktRank: Int? = null,
    ): LibraryItem =
        LibraryItem(
            id = id,
            type = type,
            name = name,
            savedAtEpochMs = savedAt,
            traktRank = traktRank,
        )
}
