package com.nuvio.app.core.ui

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

actual object PosterCardStyleStorage {
    private const val payloadKey = "poster_card_style_payload"
    private const val localSizePayloadKey = "poster_size_preferences_payload"

    actual fun loadProfilePayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun saveProfilePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadLocalSizePayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(localSizePayloadKey)

    actual fun saveLocalSizePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = localSizePayloadKey)
    }
}

internal actual fun isTabletFormFactor(): Boolean =
    UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad
