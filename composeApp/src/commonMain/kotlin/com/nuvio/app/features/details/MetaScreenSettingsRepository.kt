package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal const val MetaScreenSectionOrderMigrationVersion = 1

internal val LegacyDefaultMetaScreenSectionOrder = listOf(
    MetaScreenSectionKey.ACTIONS,
    MetaScreenSectionKey.OVERVIEW,
    MetaScreenSectionKey.PRODUCTION,
    MetaScreenSectionKey.CAST,
    MetaScreenSectionKey.COMMENTS,
    MetaScreenSectionKey.TRAILERS,
    MetaScreenSectionKey.EPISODES,
    MetaScreenSectionKey.DETAILS,
    MetaScreenSectionKey.COLLECTION,
    MetaScreenSectionKey.MORE_LIKE_THIS,
)

internal val DefaultMetaScreenSectionOrder = listOf(
    MetaScreenSectionKey.ACTIONS,
    MetaScreenSectionKey.OVERVIEW,
    MetaScreenSectionKey.PRODUCTION,
    MetaScreenSectionKey.EPISODES,
    MetaScreenSectionKey.CAST,
    MetaScreenSectionKey.COMMENTS,
    MetaScreenSectionKey.TRAILERS,
    MetaScreenSectionKey.DETAILS,
    MetaScreenSectionKey.COLLECTION,
    MetaScreenSectionKey.MORE_LIKE_THIS,
)

enum class MetaScreenSectionKey {
    ACTIONS,
    OVERVIEW,
    PRODUCTION,
    CAST,
    COMMENTS,
    TRAILERS,
    EPISODES,
    DETAILS,
    COLLECTION,
    MORE_LIKE_THIS,
    ;

    
    val canBeTabbed: Boolean
        get() = this != ACTIONS && this != OVERVIEW
}

data class MetaScreenSectionItem(
    val key: MetaScreenSectionKey,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val order: Int,
    val tabGroup: Int? = null,
)

data class MetaScreenSettingsUiState(
    val items: List<MetaScreenSectionItem> = emptyList(),
    val backgroundMode: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Normal,
    val cinematicBackground: Boolean = false,
    val heroTrailerPlayback: Boolean = false,
    val tabLayout: Boolean = false,
    val episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
    val blurUnwatchedEpisodes: Boolean = false,
)

enum class MetaScreenBackgroundMode {
    Normal,
    Cinematic,
    DominantColor,
    ;

    val usesBackdropBackground: Boolean
        get() = this != Normal

    companion object {
        fun parse(raw: String?): MetaScreenBackgroundMode? = when (raw?.lowercase()) {
            "normal" -> Normal
            "cinematic" -> Cinematic
            "dominant_color" -> DominantColor
            else -> null
        }

        fun persist(mode: MetaScreenBackgroundMode): String = when (mode) {
            Normal -> "normal"
            Cinematic -> "cinematic"
            DominantColor -> "dominant_color"
        }

        fun fromLegacyCinematic(enabled: Boolean): MetaScreenBackgroundMode =
            if (enabled) Cinematic else Normal
    }
}

enum class MetaEpisodeCardStyle {
    Horizontal,
    List,
    ;

    companion object {
        fun parse(raw: String?): MetaEpisodeCardStyle? = when (raw?.lowercase()) {
            "horizontal" -> Horizontal
            "list" -> List
            else -> null
        }

        fun persist(style: MetaEpisodeCardStyle): String = when (style) {
            Horizontal -> "horizontal"
            List -> "list"
        }
    }
}

@Serializable
internal data class StoredMetaScreenSectionPreference(
    val key: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val tabGroup: Int? = null,
)

@Serializable
internal data class StoredMetaScreenSettingsPayload(
    val items: List<StoredMetaScreenSectionPreference> = emptyList(),
    @SerialName("section_order_migration_version")
    val sectionOrderMigrationVersion: Int = 0,
    @SerialName("background_mode")
    val backgroundMode: String? = null,
    val cinematicBackground: Boolean = false,
    @SerialName("hero_trailer_playback")
    val heroTrailerPlayback: Boolean = false,
    @SerialName("tvStyleLayout")
    val tabLayout: Boolean = false,
    val episodeCardStyle: String = "horizontal",
    @SerialName("blur_unwatched_episodes")
    val blurUnwatchedEpisodes: Boolean = false,
)

internal data class MetaScreenSectionOrderMigration(
    val items: List<StoredMetaScreenSectionPreference>,
    val migrationVersion: Int,
    val migratedLegacyDefault: Boolean,
)

private val metaScreenSettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun resolveMetaScreenSectionOrderMigration(
    items: List<StoredMetaScreenSectionPreference>,
    storedMigrationVersion: Int,
): MetaScreenSectionOrderMigration {
    val shouldMigrateLegacyDefault =
        storedMigrationVersion < MetaScreenSectionOrderMigrationVersion &&
            items.isUntouchedLegacyDefaultOrder()
    val migratedItems = if (shouldMigrateLegacyDefault) {
        val newOrders = DefaultMetaScreenSectionOrder.withIndex().associate { it.value to it.index }
        items.map { item ->
            val key = runCatching { MetaScreenSectionKey.valueOf(item.key) }.getOrNull()
            item.copy(order = key?.let(newOrders::get) ?: item.order)
        }
    } else {
        items
    }
    return MetaScreenSectionOrderMigration(
        items = migratedItems,
        migrationVersion = maxOf(storedMigrationVersion, MetaScreenSectionOrderMigrationVersion),
        migratedLegacyDefault = shouldMigrateLegacyDefault,
    )
}

internal fun normalizeMetaScreenSectionPreferences(
    items: List<StoredMetaScreenSectionPreference>,
): List<StoredMetaScreenSectionPreference> {
    val storedByKey = items.mapNotNull { item ->
        val key = runCatching { MetaScreenSectionKey.valueOf(item.key) }.getOrNull()
            ?: return@mapNotNull null
        key to item
    }.toMap()
    return DefaultMetaScreenSectionOrder
        .sortedBy { key -> storedByKey[key]?.order ?: Int.MAX_VALUE }
        .mapIndexed { index, key ->
            val stored = storedByKey[key]
            StoredMetaScreenSectionPreference(
                key = key.name,
                enabled = stored?.enabled ?: true,
                order = index,
                tabGroup = stored?.tabGroup,
            )
        }
}

internal fun defaultMetaScreenSectionPreferences(): List<StoredMetaScreenSectionPreference> =
    normalizeMetaScreenSectionPreferences(emptyList())

internal fun decodeMetaScreenSettingsPayload(payload: String): StoredMetaScreenSettingsPayload? =
    payload.trim().takeIf(String::isNotEmpty)?.let { storedPayload ->
        runCatching {
            metaScreenSettingsJson.decodeFromString<StoredMetaScreenSettingsPayload>(storedPayload)
        }.getOrNull()
    }

internal fun encodeMetaScreenSettingsPayload(payload: StoredMetaScreenSettingsPayload): String =
    metaScreenSettingsJson.encodeToString(payload)

internal fun metaScreenSettingsPayloadNeedsSectionOrderMigration(payload: String): Boolean =
    decodeMetaScreenSettingsPayload(payload)?.sectionOrderMigrationVersion
        ?.let { it < MetaScreenSectionOrderMigrationVersion }
        ?: true

private fun List<StoredMetaScreenSectionPreference>.isUntouchedLegacyDefaultOrder(): Boolean {
    if (size != LegacyDefaultMetaScreenSectionOrder.size) return false
    val ordered = sortedBy(StoredMetaScreenSectionPreference::order)
    return ordered.map { it.key } == LegacyDefaultMetaScreenSectionOrder.map { it.name } &&
        ordered.map { it.order } == LegacyDefaultMetaScreenSectionOrder.indices.toList() &&
        ordered.all { it.enabled && it.tabGroup == null }
}

private data class MetaScreenSectionDefinition(
    val key: MetaScreenSectionKey,
    val titleRes: StringResource,
    val descriptionRes: StringResource,
)

object MetaScreenSettingsRepository {
    private val definitionsByKey = listOf(
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.ACTIONS,
            titleRes = Res.string.meta_section_actions_title,
            descriptionRes = Res.string.meta_section_actions_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.OVERVIEW,
            titleRes = Res.string.meta_section_overview_title,
            descriptionRes = Res.string.meta_section_overview_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.PRODUCTION,
            titleRes = Res.string.meta_section_production_title,
            descriptionRes = Res.string.meta_section_production_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.EPISODES,
            titleRes = Res.string.settings_meta_episodes,
            descriptionRes = Res.string.meta_section_episodes_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.CAST,
            titleRes = Res.string.settings_meta_cast,
            descriptionRes = Res.string.meta_section_cast_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.COMMENTS,
            titleRes = Res.string.settings_meta_comments,
            descriptionRes = Res.string.meta_section_comments_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.TRAILERS,
            titleRes = Res.string.settings_meta_trailers,
            descriptionRes = Res.string.meta_section_trailers_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.DETAILS,
            titleRes = Res.string.meta_section_details_title,
            descriptionRes = Res.string.meta_section_details_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.COLLECTION,
            titleRes = Res.string.meta_section_collection_title,
            descriptionRes = Res.string.meta_section_collection_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.MORE_LIKE_THIS,
            titleRes = Res.string.meta_section_more_like_this_title,
            descriptionRes = Res.string.meta_section_more_like_this_description,
        ),
    ).associateBy(MetaScreenSectionDefinition::key)

    private val definitions = DefaultMetaScreenSectionOrder.map(definitionsByKey::getValue)

    private val _uiState = MutableStateFlow(MetaScreenSettingsUiState())
    val uiState: StateFlow<MetaScreenSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var preferences: MutableMap<MetaScreenSectionKey, StoredMetaScreenSectionPreference> = mutableMapOf()
    private var backgroundMode: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Normal
    private var heroTrailerPlayback: Boolean = false
    private var tabLayout: Boolean = false
    private var episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal
    private var blurUnwatchedEpisodes: Boolean = false
    private var sectionOrderMigrationVersion: Int = MetaScreenSectionOrderMigrationVersion
    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val parsed = decodeMetaScreenSettingsPayload(MetaScreenSettingsStorage.loadPayload().orEmpty())
        if (parsed != null) {
            val orderMigration = resolveMetaScreenSectionOrderMigration(
                items = parsed.items,
                storedMigrationVersion = parsed.sectionOrderMigrationVersion,
            )
            sectionOrderMigrationVersion = orderMigration.migrationVersion
            backgroundMode = MetaScreenBackgroundMode.parse(parsed.backgroundMode)
                ?: MetaScreenBackgroundMode.fromLegacyCinematic(parsed.cinematicBackground)
            heroTrailerPlayback = parsed.heroTrailerPlayback
            tabLayout = parsed.tabLayout
            episodeCardStyle = MetaEpisodeCardStyle.parse(parsed.episodeCardStyle)
                ?: MetaEpisodeCardStyle.Horizontal
            blurUnwatchedEpisodes = parsed.blurUnwatchedEpisodes
            preferences = orderMigration.items.mapNotNull { item ->
                val key = runCatching { MetaScreenSectionKey.valueOf(item.key) }.getOrNull() ?: return@mapNotNull null
                key to item
            }.toMap().toMutableMap()
        }

        normalizePreferences()
        publish()
        persist()
    }

    fun onProfileChanged() {
        hasLoaded = false
        preferences.clear()
        backgroundMode = MetaScreenBackgroundMode.Normal
        heroTrailerPlayback = false
        tabLayout = false
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        blurUnwatchedEpisodes = false
        sectionOrderMigrationVersion = MetaScreenSectionOrderMigrationVersion
        _uiState.value = MetaScreenSettingsUiState()
        ensureLoaded()
    }

    fun setCinematicBackground(enabled: Boolean) {
        setBackgroundMode(MetaScreenBackgroundMode.fromLegacyCinematic(enabled))
    }

    fun setBackgroundMode(mode: MetaScreenBackgroundMode) {
        ensureLoaded()
        backgroundMode = mode
        publish()
        persist()
    }

    fun setHeroTrailerPlayback(enabled: Boolean) {
        ensureLoaded()
        heroTrailerPlayback = enabled
        publish()
        persist()
    }

    fun setTabLayout(enabled: Boolean) {
        ensureLoaded()
        tabLayout = enabled
        publish()
        persist()
    }

    fun setEpisodeCardStyle(style: MetaEpisodeCardStyle) {
        ensureLoaded()
        episodeCardStyle = style
        publish()
        persist()
    }

    fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        ensureLoaded()
        blurUnwatchedEpisodes = enabled
        publish()
        persist()
    }

    fun setTabGroup(key: MetaScreenSectionKey, groupId: Int?) {
        ensureLoaded()
        if (!key.canBeTabbed) return
        if (groupId != null) {
            // Enforce max 3 sections per group
            val currentGroupCount = preferences.count { it.value.tabGroup == groupId && it.key != key }
            if (currentGroupCount >= 3) return
        }
        updatePreference(key) { preference ->
            preference.copy(tabGroup = groupId)
        }
    }

    fun clearLocalState() {
        hasLoaded = false
        preferences.clear()
        backgroundMode = MetaScreenBackgroundMode.Normal
        heroTrailerPlayback = false
        tabLayout = false
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        blurUnwatchedEpisodes = false
        sectionOrderMigrationVersion = MetaScreenSectionOrderMigrationVersion
        _uiState.value = MetaScreenSettingsUiState()
    }

    internal fun applyFromSync(
        items: List<MetaScreenSectionItem>,
        cinematicBackground: Boolean,
        heroTrailerPlayback: Boolean = false,
        tabLayout: Boolean,
        episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
        blurUnwatchedEpisodes: Boolean = false,
        backgroundMode: MetaScreenBackgroundMode? = null,
    ) {
        ensureLoaded()
        this.backgroundMode = backgroundMode ?: MetaScreenBackgroundMode.fromLegacyCinematic(cinematicBackground)
        this.heroTrailerPlayback = heroTrailerPlayback
        this.tabLayout = tabLayout
        this.episodeCardStyle = episodeCardStyle
        this.blurUnwatchedEpisodes = blurUnwatchedEpisodes
        sectionOrderMigrationVersion = MetaScreenSectionOrderMigrationVersion
        preferences = items.associate { item ->
            item.key to StoredMetaScreenSectionPreference(
                key = item.key.name,
                enabled = item.enabled,
                order = item.order,
                tabGroup = item.tabGroup,
            )
        }.toMutableMap()
        normalizePreferences()
        publish()
        persist()
    }

    fun setEnabled(key: MetaScreenSectionKey, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        preferences.clear()
        backgroundMode = MetaScreenBackgroundMode.Normal
        heroTrailerPlayback = false
        tabLayout = false
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        blurUnwatchedEpisodes = false
        sectionOrderMigrationVersion = MetaScreenSectionOrderMigrationVersion
        normalizePreferences()
        publish()
        persist()
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val orderedKeys = definitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .map { it.key }
            .toMutableList()
        if (fromIndex !in orderedKeys.indices || toIndex !in orderedKeys.indices) return
        if (fromIndex == toIndex) return
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        orderedKeys.forEachIndexed { newIndex, sectionKey ->
            val current = preferences[sectionKey] ?: return@forEachIndexed
            preferences[sectionKey] = current.copy(order = newIndex)
        }
        publish()
        persist()
    }

    private fun updatePreference(
        key: MetaScreenSectionKey,
        transform: (StoredMetaScreenSectionPreference) -> StoredMetaScreenSectionPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: return
        preferences[key] = transform(current)
        publish()
        persist()
    }

    private fun normalizePreferences() {
        preferences = normalizeMetaScreenSectionPreferences(preferences.values.toList())
            .associateBy { item -> MetaScreenSectionKey.valueOf(item.key) }
            .toMutableMap()
    }

    private fun publish() {
        _uiState.value = MetaScreenSettingsUiState(
            items = definitions
                .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
                .map { definition ->
                    val preference = preferences[definition.key]
                    MetaScreenSectionItem(
                        key = definition.key,
                        title = localizedString(definition.titleRes),
                        description = localizedString(definition.descriptionRes),
                        enabled = preference?.enabled ?: true,
                        order = preference?.order ?: 0,
                        tabGroup = preference?.tabGroup,
                    )
                },
            backgroundMode = backgroundMode,
            cinematicBackground = backgroundMode.usesBackdropBackground,
            heroTrailerPlayback = heroTrailerPlayback,
            tabLayout = tabLayout,
            episodeCardStyle = episodeCardStyle,
            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        )
    }

    private fun persist() {
        MetaScreenSettingsStorage.savePayload(
            encodeMetaScreenSettingsPayload(
                StoredMetaScreenSettingsPayload(
                    items = preferences.values.sortedBy { it.order },
                    sectionOrderMigrationVersion = sectionOrderMigrationVersion,
                    backgroundMode = MetaScreenBackgroundMode.persist(backgroundMode),
                    cinematicBackground = backgroundMode.usesBackdropBackground,
                    heroTrailerPlayback = heroTrailerPlayback,
                    tabLayout = tabLayout,
                    episodeCardStyle = MetaEpisodeCardStyle.persist(episodeCardStyle),
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                ),
            ),
        )
    }
}
