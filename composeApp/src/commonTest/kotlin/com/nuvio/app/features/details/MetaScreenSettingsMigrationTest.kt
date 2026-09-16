package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaScreenSettingsMigrationTest {
    @Test
    fun `fresh state uses the new complete default order`() {
        val defaults = defaultMetaScreenSectionPreferences()

        assertEquals(DefaultMetaScreenSectionOrder, defaults.keys())
        assertEquals(DefaultMetaScreenSectionOrder.indices.toList(), defaults.map { it.order })
        assertTrue(defaults.all { it.enabled && it.tabGroup == null })
    }

    @Test
    fun `exact untouched legacy default moves episodes after production`() {
        val migration = resolveMetaScreenSectionOrderMigration(
            items = legacyDefaultPreferences(),
            storedMigrationVersion = 0,
        )
        val normalized = normalizeMetaScreenSectionPreferences(migration.items)

        assertTrue(migration.migratedLegacyDefault)
        assertEquals(MetaScreenSectionOrderMigrationVersion, migration.migrationVersion)
        assertEquals(DefaultMetaScreenSectionOrder, normalized.keys())
    }

    @Test
    fun `custom order remains unchanged while gaining the migration marker`() {
        val customOrder = LegacyDefaultMetaScreenSectionOrder.toMutableList().apply {
            add(0, removeAt(indexOf(MetaScreenSectionKey.CAST)))
        }
        val custom = customOrder.mapIndexed { index, key -> preference(key, index) }
        val migration = resolveMetaScreenSectionOrderMigration(custom, storedMigrationVersion = 0)

        assertFalse(migration.migratedLegacyDefault)
        assertEquals(customOrder, normalizeMetaScreenSectionPreferences(migration.items).keys())
        assertEquals(MetaScreenSectionOrderMigrationVersion, migration.migrationVersion)
    }

    @Test
    fun `saved legacy order remains intentional after migration version one`() {
        val migration = resolveMetaScreenSectionOrderMigration(
            items = legacyDefaultPreferences(),
            storedMigrationVersion = MetaScreenSectionOrderMigrationVersion,
        )

        assertFalse(migration.migratedLegacyDefault)
        assertEquals(
            LegacyDefaultMetaScreenSectionOrder,
            normalizeMetaScreenSectionPreferences(migration.items).keys(),
        )
    }

    @Test
    fun `enabled states and tab groups prevent default migration and survive normalization`() {
        val customized = legacyDefaultPreferences().map { item ->
            when (item.key) {
                MetaScreenSectionKey.CAST.name -> item.copy(enabled = false)
                MetaScreenSectionKey.COMMENTS.name -> item.copy(tabGroup = 2)
                else -> item
            }
        }
        val migration = resolveMetaScreenSectionOrderMigration(customized, storedMigrationVersion = 0)
        val normalized = normalizeMetaScreenSectionPreferences(migration.items).associateBy { it.key }

        assertFalse(migration.migratedLegacyDefault)
        assertEquals(LegacyDefaultMetaScreenSectionOrder, normalized.values.sortedBy { it.order }.keys())
        assertFalse(normalized.getValue(MetaScreenSectionKey.CAST.name).enabled)
        assertEquals(2, normalized.getValue(MetaScreenSectionKey.COMMENTS.name).tabGroup)
    }

    @Test
    fun `reset defaults match fresh defaults`() {
        assertEquals(
            defaultMetaScreenSectionPreferences(),
            normalizeMetaScreenSectionPreferences(emptyList()),
        )
    }

    @Test
    fun `malformed and partial payloads remain compatible`() {
        assertNull(decodeMetaScreenSettingsPayload("{not-json"))

        val partial = decodeMetaScreenSettingsPayload(
            """{"items":[{"key":"CAST","enabled":false,"order":4,"tabGroup":3}]}""",
        )
        val migration = resolveMetaScreenSectionOrderMigration(
            items = partial?.items.orEmpty(),
            storedMigrationVersion = partial?.sectionOrderMigrationVersion ?: 0,
        )
        val normalized = normalizeMetaScreenSectionPreferences(migration.items).associateBy { it.key }

        assertFalse(migration.migratedLegacyDefault)
        assertFalse(normalized.getValue(MetaScreenSectionKey.CAST.name).enabled)
        assertEquals(3, normalized.getValue(MetaScreenSectionKey.CAST.name).tabGroup)
        assertTrue(normalized.getValue(MetaScreenSectionKey.EPISODES.name).enabled)
    }

    @Test
    fun `legacy synced payload is rewritten once and stays migrated after reload`() {
        val remotePayload = encodeMetaScreenSettingsPayload(
            StoredMetaScreenSettingsPayload(items = legacyDefaultPreferences()),
        )
        assertTrue(metaScreenSettingsPayloadNeedsSectionOrderMigration(remotePayload))

        val decodedRemote = requireNotNull(decodeMetaScreenSettingsPayload(remotePayload))
        val migration = resolveMetaScreenSectionOrderMigration(
            items = decodedRemote.items,
            storedMigrationVersion = decodedRemote.sectionOrderMigrationVersion,
        )
        val rewrittenPayload = encodeMetaScreenSettingsPayload(
            decodedRemote.copy(
                items = normalizeMetaScreenSectionPreferences(migration.items),
                sectionOrderMigrationVersion = migration.migrationVersion,
            ),
        )
        val reloaded = requireNotNull(decodeMetaScreenSettingsPayload(rewrittenPayload))
        val secondMigration = resolveMetaScreenSectionOrderMigration(
            items = reloaded.items,
            storedMigrationVersion = reloaded.sectionOrderMigrationVersion,
        )

        assertFalse(metaScreenSettingsPayloadNeedsSectionOrderMigration(rewrittenPayload))
        assertFalse(secondMigration.migratedLegacyDefault)
        assertEquals(DefaultMetaScreenSectionOrder, normalizeMetaScreenSectionPreferences(secondMigration.items).keys())
    }

    private fun legacyDefaultPreferences(): List<StoredMetaScreenSectionPreference> =
        LegacyDefaultMetaScreenSectionOrder.mapIndexed(::preference)

    private fun preference(index: Int, key: MetaScreenSectionKey): StoredMetaScreenSectionPreference =
        preference(key, index)

    private fun preference(key: MetaScreenSectionKey, order: Int): StoredMetaScreenSectionPreference =
        StoredMetaScreenSectionPreference(
            key = key.name,
            order = order,
        )

    private fun List<StoredMetaScreenSectionPreference>.keys(): List<MetaScreenSectionKey> =
        map { MetaScreenSectionKey.valueOf(it.key) }
}
