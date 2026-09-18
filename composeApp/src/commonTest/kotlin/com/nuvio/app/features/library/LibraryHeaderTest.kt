package com.nuvio.app.features.library

import com.nuvio.app.core.ui.NuvioPosterAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryHeaderTest {
    @Test
    fun `Library header chooses chips then dropdown then stacking from measured fit`() {
        assertEquals(
            LibraryHeaderPresentation.InlineChoices,
            resolveLibraryHeaderPresentation(600, 440, 150, 140, 12),
        )
        assertEquals(
            LibraryHeaderPresentation.InlineDropdown,
            resolveLibraryHeaderPresentation(400, 440, 150, 140, 12),
        )
        assertEquals(
            LibraryHeaderPresentation.StackedDropdown,
            resolveLibraryHeaderPresentation(280, 440, 150, 140, 12),
        )
    }

    @Test
    fun `Cloud files only appears when a compatible provider exists`() {
        assertEquals(
            listOf(LibraryViewMode.All, LibraryViewMode.Saved, LibraryViewMode.Downloaded),
            availableLibraryViewModes(hasCloudLibraryProvider = false),
        )
        assertEquals(
            listOf(
                LibraryViewMode.All,
                LibraryViewMode.Saved,
                LibraryViewMode.Downloaded,
                LibraryViewMode.Cloud,
            ),
            availableLibraryViewModes(hasCloudLibraryProvider = true),
        )
    }

    @Test
    fun `Downloads actions keep Manage Activity and View order`() {
        assertEquals(
            listOf(
                LibraryHeaderAction.ManageDownloads,
                LibraryHeaderAction.DownloadActivity,
                LibraryHeaderAction.ViewMode,
            ),
            libraryHeaderActionOrder(
                sourceMode = LibraryViewMode.Downloaded,
                hasDownloadActivity = true,
            ),
        )
        assertEquals(
            listOf(
                LibraryHeaderAction.DownloadActivity,
                LibraryHeaderAction.ViewMode,
            ),
            libraryHeaderActionOrder(
                sourceMode = LibraryViewMode.Saved,
                hasDownloadActivity = true,
            ),
        )
        assertEquals(
            listOf(LibraryHeaderAction.DownloadActivity),
            libraryHeaderActionOrder(
                sourceMode = LibraryViewMode.Cloud,
                hasDownloadActivity = true,
            ),
        )
    }

    @Test
    fun `Manage and Done keep enabled semantics and the muted color family`() {
        val empty = libraryManageActionPresentation(
            isManaging = false,
            hasDownloadedItems = false,
        )
        val ready = libraryManageActionPresentation(
            isManaging = false,
            hasDownloadedItems = true,
        )
        val managing = libraryManageActionPresentation(
            isManaging = true,
            hasDownloadedItems = false,
        )

        assertEquals(LibraryManageActionLabel.Manage, empty.label)
        assertFalse(empty.enabled)
        assertTrue(ready.enabled)
        assertEquals(LibraryManageActionLabel.Done, managing.label)
        assertTrue(managing.enabled)
        assertEquals(LibraryHeaderActionColorFamily.Muted, ready.colorFamily)
        assertEquals(LibraryHeaderActionColorFamily.Muted, managing.colorFamily)
    }

    @Test
    fun `offline availability marks downloads and blocks only online titles`() {
        assertEquals(
            NuvioPosterAvailability.Downloaded,
            libraryPosterAvailability(isOfflineLike = true, isDownloaded = true),
        )
        assertEquals(
            NuvioPosterAvailability.InternetRequired,
            libraryPosterAvailability(isOfflineLike = true, isDownloaded = false),
        )
        assertEquals(
            NuvioPosterAvailability.None,
            libraryPosterAvailability(isOfflineLike = false, isDownloaded = false),
        )
        assertTrue(shouldOpenLibraryTitle(isOfflineLike = true, isDownloaded = true))
        assertTrue(shouldOpenLibraryTitle(isOfflineLike = false, isDownloaded = false))
        assertFalse(shouldOpenLibraryTitle(isOfflineLike = true, isDownloaded = false))
    }
}
