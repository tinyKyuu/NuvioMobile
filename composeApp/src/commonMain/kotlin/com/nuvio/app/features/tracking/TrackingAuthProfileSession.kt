package com.nuvio.app.features.tracking

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class TrackingAuthProfileSession(
    val profileId: Int,
    val generation: Long,
)

internal class TrackingAuthProfileSessionGuard(initialProfileId: Int) {
    private val lock = SynchronizedObject()
    private var currentProfileId: Int = initialProfileId
    private var currentGeneration: Long = 0L

    val profileId: Int
        get() = synchronized(lock) { currentProfileId }

    val generation: Long
        get() = synchronized(lock) { currentGeneration }

    fun moveTo(profileId: Int): TrackingAuthProfileSession = synchronized(lock) {
        currentProfileId = profileId
        currentGeneration += 1L
        captureLocked()
    }

    fun invalidate(): TrackingAuthProfileSession = synchronized(lock) {
        currentGeneration += 1L
        captureLocked()
    }

    fun capture(): TrackingAuthProfileSession = synchronized(lock) { captureLocked() }

    fun isCurrent(session: TrackingAuthProfileSession): Boolean = synchronized(lock) {
        session.profileId == currentProfileId && session.generation == currentGeneration
    }

    private fun captureLocked(): TrackingAuthProfileSession = TrackingAuthProfileSession(
        profileId = currentProfileId,
        generation = currentGeneration,
    )
}
