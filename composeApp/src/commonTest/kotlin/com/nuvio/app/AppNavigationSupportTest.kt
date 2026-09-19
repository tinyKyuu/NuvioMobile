package com.nuvio.app

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppNavigationSupportTest {
    private val downloadedOnly = LibraryItem(
        id = "downloaded-only",
        type = "movie",
        name = "Downloaded only",
        savedAtEpochMs = 0L,
    )

    @Test
    fun `downloaded-only poster adds to the local library`() {
        assertEquals(
            PosterLibraryMembershipAction.AddLocal,
            posterLibraryMembershipAction(
                isSaved = false,
                isRemoteLibrarySource = false,
                membershipListKey = null,
            ),
        )
    }

    @Test
    fun `downloaded-only poster opens remote membership selection`() {
        assertEquals(
            PosterLibraryMembershipAction.AddRemote,
            posterLibraryMembershipAction(
                isSaved = false,
                isRemoteLibrarySource = true,
                membershipListKey = null,
            ),
        )
    }

    @Test
    fun `All titles display grouping never becomes a tracking list key`() {
        val target = libraryPosterActionTarget(
            item = downloadedOnly.copy(listKeys = setOf("trakt:watchlist")),
            displaySectionKey = "movie",
        )

        assertEquals("movie", target.libraryDisplaySectionKey)
        assertNull(target.libraryMembershipListKey)
        assertNull(
            libraryMembershipListKey(
                sourceMode = LibrarySourceMode.TRAKT,
                displaySectionKey = "movie",
                providerSectionKeys = setOf("trakt:watchlist"),
            ),
        )
        assertEquals(
            PosterLibraryMembershipAction.RemoveRemoteSource,
            posterLibraryMembershipAction(
                isSaved = true,
                isRemoteLibrarySource = true,
                membershipListKey = target.libraryMembershipListKey,
            ),
        )
    }

    @Test
    fun `real provider list remains a targeted remote removal`() {
        val membershipListKey = libraryMembershipListKey(
            sourceMode = LibrarySourceMode.TRAKT,
            displaySectionKey = "trakt:watchlist",
            providerSectionKeys = setOf("trakt:watchlist"),
        )
        val target = libraryPosterActionTarget(
            item = downloadedOnly,
            displaySectionKey = "trakt:watchlist",
            membershipListKey = membershipListKey,
        )

        assertEquals(
            PosterLibraryMembershipAction.RemoveRemoteList,
            posterLibraryMembershipAction(
                isSaved = true,
                isRemoteLibrarySource = true,
                membershipListKey = target.libraryMembershipListKey,
            ),
        )
    }
}
