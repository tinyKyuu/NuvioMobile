package com.nuvio.app.features.player

import platform.Foundation.NSUUID

internal actual fun withIsolatedTrackPreferences(block: (String) -> Unit) {
    val id = "subtitle-restoration-test-${NSUUID().UUIDString}"
    try {
        block(id)
    } finally {
        // Remove only this test's random content key from the real defaults adapter.
        PlayerTrackPreferenceStorage.save(id, PersistedPlayerTrackPreference())
    }
}
