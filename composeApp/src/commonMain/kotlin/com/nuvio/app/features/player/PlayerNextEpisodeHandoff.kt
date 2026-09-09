package com.nuvio.app.features.player

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun handoffNextEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    isCurrentRequest: () -> Boolean,
    onResolved: (StreamItem) -> Unit,
    onUnresolved: (DirectDebridPlayableResult) -> Unit,
    resolve: suspend (StreamItem, MetaVideo) -> DirectDebridPlayableResult = { source, video ->
        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(source)) {
            DirectDebridPlaybackResolver.resolveToPlayableStream(source, video.season, video.episode)
        } else {
            DirectDebridPlayableResult.Success(source)
        }
    },
) {
    currentCoroutineContext().ensureActive()
    if (!isCurrentRequest()) return
    val result = resolve(stream, episode)
    currentCoroutineContext().ensureActive()
    if (!isCurrentRequest()) return
    when (result) {
        is DirectDebridPlayableResult.Success -> onResolved(result.stream)
        else -> onUnresolved(result)
    }
}
