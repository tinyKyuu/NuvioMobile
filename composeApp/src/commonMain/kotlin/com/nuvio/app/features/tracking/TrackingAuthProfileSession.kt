package com.nuvio.app.features.tracking

internal data class TrackingAuthProfileSession(
    val profileId: Int,
    val generation: Long,
)

internal class TrackingAuthProfileSessionGuard(initialProfileId: Int) {
    var profileId: Int = initialProfileId
        private set

    var generation: Long = 0L
        private set

    fun moveTo(profileId: Int): TrackingAuthProfileSession {
        this.profileId = profileId
        generation += 1L
        return capture()
    }

    fun invalidate(): TrackingAuthProfileSession {
        generation += 1L
        return capture()
    }

    fun capture(): TrackingAuthProfileSession = TrackingAuthProfileSession(
        profileId = profileId,
        generation = generation,
    )

    fun isCurrent(session: TrackingAuthProfileSession): Boolean =
        session.profileId == profileId && session.generation == generation
}
