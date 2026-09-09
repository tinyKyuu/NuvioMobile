package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

internal data class AddonSubtitleFetchState(val videoId: String? = null, val isComplete: Boolean = false)

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null
    private val publicationLock = SynchronizedObject()
    private var fetchGeneration = 0
    private val _fetchState = MutableStateFlow(AddonSubtitleFetchState())
    internal val fetchState = _fetchState.asStateFlow()

    fun fetchAddonSubtitles(type: String, videoId: String?) {
        startFetch(videoId, canFetch = type.isNotBlank() && !videoId.isNullOrBlank()) { publishSubtitles, publishError ->
            val requestType = canonicalSubtitleType(type)
            val requestedVideoId = requireNotNull(videoId)

            val addons = AddonRepository.uiState.value.addons.enabledAddons()
            val subtitleAddons = addons.filter { addon ->
                val manifest = addon.manifest ?: return@filter false
                val subtitleResource = manifest.resources.find { it.name.isSubtitleResourceName() } ?: return@filter false
                subtitleResource.supportsSubtitleType(requestType, requestedVideoId)
            }

            if (subtitleAddons.isEmpty()) {
                return@startFetch
            }

            supervisorScope {
                subtitleAddons.map { addon ->
                    async {
                        val manifest = addon.manifest ?: return@async
                        val subtitleUrl = buildAddonResourceUrl(
                            manifestUrl = manifest.transportUrl,
                            resource = "subtitles",
                            type = requestType,
                            id = requestedVideoId,
                        )

                        try {
                            val response = withTimeoutOrNull(10_000L) {
                                withContext(Dispatchers.Default) {
                                    fetchAddonResponseText(subtitleUrl)
                                }
                            } ?: return@async

                            val parsed = json.parseToJsonElement(response).jsonObject
                            val subtitlesArray = parsed["subtitles"]?.jsonArray ?: return@async

                            val addonSubs = mutableListOf<AddonSubtitle>()
                            for (element in subtitlesArray) {
                                val obj = element.jsonObject
                                val id = obj.stringValue("id")
                                    ?: "${manifest.id}_${addonSubs.size}"
                                val url = obj.stringValue("url") ?: continue
                                val rawLang = obj.subtitleLanguage() ?: "unknown"
                                val normalizedLang = normalizeLanguageCode(rawLang) ?: rawLang

                                addonSubs.add(
                                    AddonSubtitle(
                                        id = id,
                                        url = url,
                                        language = normalizedLang,
                                        display = getString(
                                            Res.string.player_addon_subtitle_display_format,
                                            getLanguageLabelForCode(rawLang),
                                            addon.displayTitle,
                                        ),
                                        addonName = addon.displayTitle,
                                        sourceVideoId = requestedVideoId,
                                    )
                                )
                            }

                            if (addonSubs.isNotEmpty()) publishSubtitles(addonSubs)
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                        }
                    }
                }.awaitAll()
            }

            if (_addonSubtitles.value.isEmpty()) {
                publishError(getString(Res.string.compose_player_no_subtitles_found))
            }
        }
    }

    // The worker runs outside the lock. Only ownership and publication are serialized.
    // Keeping this boundary explicit also allows tests to hold/release a real worker
    // without network requests or changing configured addon records.
    internal fun startFetch(
        videoId: String?,
        canFetch: Boolean,
        fetch: suspend (publishSubtitles: (List<AddonSubtitle>) -> Unit, publishError: (String) -> Unit) -> Unit,
    ): Job? {
        val (previous, next) = synchronized(publicationLock) {
            val previous = activeFetchJob
            val generation = ++fetchGeneration
            _fetchState.value = AddonSubtitleFetchState(videoId)
            _addonSubtitles.value = emptyList()
            _error.value = null
            _isLoading.value = canFetch
            val next = if (canFetch) {
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        fetch(
                            { subtitles -> publishIfCurrent(generation) { _addonSubtitles.value += subtitles } },
                            { error -> publishIfCurrent(generation) { _error.value = error } },
                        )
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                    } finally {
                        publishIfCurrent(generation) {
                            _isLoading.value = false
                            _fetchState.value = AddonSubtitleFetchState(videoId, isComplete = true)
                        }
                    }
                }
            } else {
                _fetchState.value = AddonSubtitleFetchState(videoId, isComplete = true)
                null
            }
            activeFetchJob = next
            previous to next
        }
        // Cancellation may run callbacks; neither it nor starting work holds the lock.
        previous?.cancel()
        next?.start()
        return next
    }

    private inline fun publishIfCurrent(generation: Int, publish: () -> Unit) {
        synchronized(publicationLock) {
            if (generation == fetchGeneration) publish()
        }
    }

    fun clear() {
        val previous = synchronized(publicationLock) {
            fetchGeneration++
            val previous = activeFetchJob
            activeFetchJob = null
            _fetchState.value = AddonSubtitleFetchState()
            _addonSubtitles.value = emptyList()
            _isLoading.value = false
            _error.value = null
            previous
        }
        previous?.cancel()
    }
}

private fun canonicalSubtitleType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

private fun String.isSubtitleResourceName(): Boolean =
    equals("subtitles", ignoreCase = true) || equals("subtitle", ignoreCase = true)

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonical = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { canonicalSubtitleType(it).equals(canonical, ignoreCase = true) }
    if (!typeMatches) return false
    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

private fun JsonObject.subtitleLanguage(): String? =
    stringValue("lang")
        ?: stringValue("language")
        ?: stringValue("languageCode")
        ?: stringValue("locale")
        ?: stringValue("label")

private fun JsonObject.stringValue(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
