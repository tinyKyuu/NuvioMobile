package com.nuvio.app.features.library

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryHeaderTest {
    @Test
    fun `wide and narrow widths keep Library actions out of the connection row`() {
        val phone = libraryHeaderLayoutForWidth(390.dp)
        val narrowTablet = libraryHeaderLayoutForWidth(519.dp)
        val tablet = libraryHeaderLayoutForWidth(520.dp)
        val wideTablet = libraryHeaderLayoutForWidth(1_024.dp)

        assertEquals(LibraryHeaderArrangement.Narrow, phone.arrangement)
        assertEquals(LibraryHeaderRow.Tertiary, phone.actionsRow)
        assertEquals(LibraryHeaderArrangement.Narrow, narrowTablet.arrangement)
        assertEquals(LibraryHeaderRow.Tertiary, narrowTablet.actionsRow)
        assertEquals(LibraryHeaderArrangement.Wide, tablet.arrangement)
        assertEquals(LibraryHeaderRow.Secondary, tablet.actionsRow)
        assertEquals(LibraryHeaderArrangement.Wide, wideTablet.arrangement)
        listOf(phone, narrowTablet, tablet, wideTablet).forEach { layout ->
            assertEquals(LibraryHeaderRow.Title, layout.connectionRow)
            assertEquals(LibraryHeaderRow.Secondary, layout.sourcesRow)
            assertFalse(layout.actionsRow == layout.connectionRow)
        }
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

        assertEquals(LibraryManageActionLabel.ManageDownloads, empty.label)
        assertFalse(empty.enabled)
        assertTrue(ready.enabled)
        assertEquals(LibraryManageActionLabel.Done, managing.label)
        assertTrue(managing.enabled)
        assertEquals(LibraryHeaderActionColorFamily.Muted, ready.colorFamily)
        assertEquals(LibraryHeaderActionColorFamily.Muted, managing.colorFamily)
    }
}
