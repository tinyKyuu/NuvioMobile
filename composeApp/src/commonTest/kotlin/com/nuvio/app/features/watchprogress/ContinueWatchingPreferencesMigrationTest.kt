package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingPreferencesMigrationTest {
    @Test
    fun `legacy card default migrates to poster once`() {
        val migration = resolveContinueWatchingStyleMigration(
            storedStyle = ContinueWatchingSectionStyle.Card,
            storedMigrationVersion = 0,
        )

        assertEquals(ContinueWatchingSectionStyle.Poster, migration.style)
        assertEquals(ContinueWatchingPosterDefaultMigrationVersion, migration.migrationVersion)
        assertTrue(migration.shouldPersist)
    }

    @Test
    fun `explicit card remains card after migration`() {
        val secondLoad = resolveContinueWatchingStyleMigration(
            storedStyle = ContinueWatchingSectionStyle.Card,
            storedMigrationVersion = ContinueWatchingPosterDefaultMigrationVersion,
        )

        assertEquals(ContinueWatchingSectionStyle.Card, secondLoad.style)
        assertFalse(secondLoad.shouldPersist)
    }

    @Test
    fun `remotely restored legacy card is migrated`() {
        val remotePayload = """{"style":"Card","styleMigrationVersion":0}"""
        val restoredLegacyPayload = resolveContinueWatchingStyleMigration(
            storedStyle = ContinueWatchingSectionStyle.Card,
            storedMigrationVersion = 0,
        )

        assertEquals(ContinueWatchingSectionStyle.Poster, restoredLegacyPayload.style)
        assertTrue(restoredLegacyPayload.shouldPersist)
        assertTrue(continueWatchingPayloadNeedsStyleMigration(remotePayload))
    }

    @Test
    fun `remotely restored explicit card is already migration safe`() {
        val remotePayload = """{"style":"Card","styleMigrationVersion":1}"""

        assertFalse(continueWatchingPayloadNeedsStyleMigration(remotePayload))
    }

    @Test
    fun `new preference state defaults to poster`() {
        assertEquals(
            ContinueWatchingSectionStyle.Poster,
            ContinueWatchingPreferencesUiState().style,
        )
    }
}
