package com.nuvio.app.features.watchtogether.protocol

internal data class SourceTimeMapping(
    val positionMs: Long,
    val clamped: Boolean,
)

internal object SourceTimeMapper {
    fun localPosition(
        canonicalPositionMs: Long,
        sourceOffsetMs: Long,
    ): SourceTimeMapping = translate(canonicalPositionMs, sourceOffsetMs)

    fun canonicalPosition(
        localPositionMs: Long,
        sourceOffsetMs: Long,
    ): SourceTimeMapping = translate(localPositionMs, -sourceOffsetMs)

    private fun translate(positionMs: Long, deltaMs: Long): SourceTimeMapping {
        require(positionMs in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER)
        require(deltaMs in -WATCH_TOGETHER_MAX_SAFE_INTEGER..WATCH_TOGETHER_MAX_SAFE_INTEGER)
        val translated = positionMs + deltaMs
        val bounded = translated.coerceIn(0L, WATCH_TOGETHER_MAX_SAFE_INTEGER)
        return SourceTimeMapping(
            positionMs = bounded,
            clamped = bounded != translated,
        )
    }
}
