package com.nuvio.app.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val CompactPosterCardWidthDp = 104
internal const val DensePosterCardWidthDp = 112
internal const val StandardPosterCardWidthDp = 120
internal const val DefaultPosterCardWidthDp = 126
internal const val ComfortPosterCardWidthDp = 134
internal const val LargePosterCardWidthDp = 140
internal const val ExtraLargePosterCardWidthDp = 160
internal const val DefaultPosterCardHeightDp = 189
internal const val DefaultPosterCardCornerRadiusDp = 12
internal const val AutomaticTabletLargeMinimumWidthDp = 700f

private const val PosterSizePayloadVersion = 1

@Serializable
enum class PosterSizeMode {
    Automatic,
    Manual,
}

data class PosterSizePreference(
    val mode: PosterSizeMode = PosterSizeMode.Automatic,
    val manualWidthDp: Int? = null,
)

data class PosterCardDimensions(
    val widthDp: Int,
    val heightDp: Int,
)

@Serializable
private data class StoredPosterCardStylePreferences(
    // Kept for older clients and profile payload compatibility. New clients use
    // the installation-local poster size payload instead.
    val widthDp: Int = DefaultPosterCardWidthDp,
    val heightDp: Int = DefaultPosterCardHeightDp,
    val cornerRadiusDp: Int = DefaultPosterCardCornerRadiusDp,
    val catalogLandscapeModeEnabled: Boolean = false,
    val hideLabelsEnabled: Boolean = false,
)

@Serializable
private data class StoredPosterSizePreference(
    val version: Int = PosterSizePayloadVersion,
    val mode: PosterSizeMode = PosterSizeMode.Automatic,
    val manualWidthDp: Int? = null,
)

data class PosterCardStyleUiState(
    val sizeMode: PosterSizeMode = PosterSizeMode.Automatic,
    val manualWidthDp: Int? = null,
    val widthDp: Int = DefaultPosterCardWidthDp,
    val heightDp: Int = DefaultPosterCardHeightDp,
    val cornerRadiusDp: Int = DefaultPosterCardCornerRadiusDp,
    val catalogLandscapeModeEnabled: Boolean = false,
    val hideLabelsEnabled: Boolean = false,
)

internal data class PosterCardProfileSyncState(
    val cornerRadiusDp: Int,
    val catalogLandscapeModeEnabled: Boolean,
    val hideLabelsEnabled: Boolean,
)

internal data class PosterSizeLoadResult(
    val preference: PosterSizePreference,
    val payloadToPersist: String?,
)

private val posterCardStyleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun posterCardDimensions(widthDp: Int): PosterCardDimensions {
    val safeWidthDp = widthDp.takeIf { it > 0 } ?: DefaultPosterCardWidthDp
    return PosterCardDimensions(
        widthDp = safeWidthDp,
        heightDp = (safeWidthDp * 3) / 2,
    )
}

internal fun resolvePosterCardDimensions(
    preference: PosterSizePreference,
    isTabletFormFactor: Boolean,
    usableWindowWidthDp: Float,
): PosterCardDimensions {
    val widthDp = when (preference.mode) {
        PosterSizeMode.Manual -> preference.manualWidthDp
            ?.takeIf { it > 0 }
            ?: DefaultPosterCardWidthDp
        PosterSizeMode.Automatic -> if (
            isTabletFormFactor && usableWindowWidthDp >= AutomaticTabletLargeMinimumWidthDp
        ) {
            LargePosterCardWidthDp
        } else {
            DefaultPosterCardWidthDp
        }
    }
    return posterCardDimensions(widthDp)
}

internal fun resolvePosterCardStyle(
    state: PosterCardStyleUiState,
    isTabletFormFactor: Boolean,
    usableWindowWidthDp: Float,
): PosterCardStyleUiState {
    val dimensions = resolvePosterCardDimensions(
        preference = PosterSizePreference(
            mode = state.sizeMode,
            manualWidthDp = state.manualWidthDp,
        ),
        isTabletFormFactor = isTabletFormFactor,
        usableWindowWidthDp = usableWindowWidthDp,
    )
    return state.copy(
        widthDp = dimensions.widthDp,
        heightDp = dimensions.heightDp,
    )
}

internal fun PosterCardStyleUiState.profileSyncState(): PosterCardProfileSyncState =
    PosterCardProfileSyncState(
        cornerRadiusDp = cornerRadiusDp,
        catalogLandscapeModeEnabled = catalogLandscapeModeEnabled,
        hideLabelsEnabled = hideLabelsEnabled,
    )

internal fun encodePosterSizePreference(preference: PosterSizePreference): String =
    posterCardStyleJson.encodeToString(
        StoredPosterSizePreference(
            mode = preference.mode,
            manualWidthDp = preference.manualWidthDp?.takeIf { it > 0 },
        ),
    )

internal fun loadPosterSizePreference(
    localPayload: String?,
    legacyWidthDp: Int?,
): PosterSizeLoadResult {
    val stored = localPayload
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { payload ->
            runCatching {
                posterCardStyleJson.decodeFromString<StoredPosterSizePreference>(payload)
            }.getOrNull()
        }

    if (stored != null && stored.version >= PosterSizePayloadVersion) {
        val preference = when (stored.mode) {
            PosterSizeMode.Automatic -> PosterSizePreference()
            PosterSizeMode.Manual -> PosterSizePreference(
                mode = PosterSizeMode.Manual,
                manualWidthDp = stored.manualWidthDp
                    ?.takeIf { it > 0 }
                    ?: DefaultPosterCardWidthDp,
            )
        }
        return PosterSizeLoadResult(preference = preference, payloadToPersist = null)
    }

    val preference = legacyWidthDp
        ?.takeIf { it > 0 && it != DefaultPosterCardWidthDp }
        ?.let { legacyWidth ->
            PosterSizePreference(
                mode = PosterSizeMode.Manual,
                manualWidthDp = legacyWidth,
            )
        }
        ?: PosterSizePreference()
    return PosterSizeLoadResult(
        preference = preference,
        payloadToPersist = encodePosterSizePreference(preference),
    )
}

object PosterCardStyleRepository {
    private val _uiState = MutableStateFlow(PosterCardStyleUiState())
    val uiState: StateFlow<PosterCardStyleUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var legacyProfileWidthDp = DefaultPosterCardWidthDp
    private var legacyProfileHeightDp = DefaultPosterCardHeightDp

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        legacyProfileWidthDp = DefaultPosterCardWidthDp
        legacyProfileHeightDp = DefaultPosterCardHeightDp
        _uiState.value = PosterCardStyleUiState()
    }

    fun setAutomaticSize() {
        ensureLoaded()
        if (_uiState.value.sizeMode == PosterSizeMode.Automatic) return
        val dimensions = posterCardDimensions(DefaultPosterCardWidthDp)
        _uiState.value = _uiState.value.copy(
            sizeMode = PosterSizeMode.Automatic,
            manualWidthDp = null,
            widthDp = dimensions.widthDp,
            heightDp = dimensions.heightDp,
        )
        persistLocalSize()
    }

    fun setWidthDp(widthDp: Int) {
        ensureLoaded()
        val dimensions = posterCardDimensions(widthDp)
        if (
            _uiState.value.sizeMode == PosterSizeMode.Manual &&
            _uiState.value.manualWidthDp == dimensions.widthDp
        ) {
            return
        }
        _uiState.value = _uiState.value.copy(
            sizeMode = PosterSizeMode.Manual,
            manualWidthDp = dimensions.widthDp,
            widthDp = dimensions.widthDp,
            heightDp = dimensions.heightDp,
        )
        persistLocalSize()
    }

    fun setCornerRadiusDp(cornerRadiusDp: Int) {
        ensureLoaded()
        if (_uiState.value.cornerRadiusDp == cornerRadiusDp) return
        _uiState.value = _uiState.value.copy(cornerRadiusDp = cornerRadiusDp)
        persistProfileStyle()
    }

    fun setCatalogLandscapeModeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.catalogLandscapeModeEnabled == enabled) return
        _uiState.value = _uiState.value.copy(catalogLandscapeModeEnabled = enabled)
        persistProfileStyle()
    }

    fun setHideLabelsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.hideLabelsEnabled == enabled) return
        _uiState.value = _uiState.value.copy(hideLabelsEnabled = enabled)
        persistProfileStyle()
    }

    fun resetToDefaults() {
        ensureLoaded()
        legacyProfileWidthDp = DefaultPosterCardWidthDp
        legacyProfileHeightDp = DefaultPosterCardHeightDp
        _uiState.value = PosterCardStyleUiState()
        persistLocalSize()
        persistProfileStyle()
    }

    private fun loadFromDisk() {
        hasLoaded = true

        val profilePayload = PosterCardStyleStorage.loadProfilePayload().orEmpty().trim()
        val storedProfile = profilePayload
            .takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching {
                    posterCardStyleJson.decodeFromString<StoredPosterCardStylePreferences>(payload)
                }.getOrNull()
            }
            ?: StoredPosterCardStylePreferences()

        legacyProfileWidthDp = storedProfile.widthDp.takeIf { it > 0 } ?: DefaultPosterCardWidthDp
        legacyProfileHeightDp = storedProfile.heightDp.takeIf { it > 0 }
            ?: posterCardDimensions(legacyProfileWidthDp).heightDp

        val sizeLoad = loadPosterSizePreference(
            localPayload = PosterCardStyleStorage.loadLocalSizePayload(),
            legacyWidthDp = legacyProfileWidthDp,
        )
        sizeLoad.payloadToPersist?.let(PosterCardStyleStorage::saveLocalSizePayload)
        val dimensions = resolvePosterCardDimensions(
            preference = sizeLoad.preference,
            isTabletFormFactor = false,
            usableWindowWidthDp = 0f,
        )

        _uiState.value = PosterCardStyleUiState(
            sizeMode = sizeLoad.preference.mode,
            manualWidthDp = sizeLoad.preference.manualWidthDp,
            widthDp = dimensions.widthDp,
            heightDp = dimensions.heightDp,
            cornerRadiusDp = storedProfile.cornerRadiusDp.coerceAtLeast(0),
            catalogLandscapeModeEnabled = storedProfile.catalogLandscapeModeEnabled,
            hideLabelsEnabled = storedProfile.hideLabelsEnabled,
        )
    }

    private fun persistLocalSize() {
        PosterCardStyleStorage.saveLocalSizePayload(
            encodePosterSizePreference(
                PosterSizePreference(
                    mode = _uiState.value.sizeMode,
                    manualWidthDp = _uiState.value.manualWidthDp,
                ),
            ),
        )
    }

    private fun persistProfileStyle() {
        PosterCardStyleStorage.saveProfilePayload(
            posterCardStyleJson.encodeToString(
                StoredPosterCardStylePreferences(
                    widthDp = legacyProfileWidthDp,
                    heightDp = legacyProfileHeightDp,
                    cornerRadiusDp = _uiState.value.cornerRadiusDp,
                    catalogLandscapeModeEnabled = _uiState.value.catalogLandscapeModeEnabled,
                    hideLabelsEnabled = _uiState.value.hideLabelsEnabled,
                ),
            ),
        )
    }
}
