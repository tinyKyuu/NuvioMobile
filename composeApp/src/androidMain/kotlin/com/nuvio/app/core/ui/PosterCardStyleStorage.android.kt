package com.nuvio.app.core.ui

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object PosterCardStyleStorage {
    private const val profilePreferencesName = "nuvio_poster_card_style"
    private const val localSizePreferencesName = "nuvio_poster_size"
    private const val payloadKey = "poster_card_style_payload"
    private const val localSizePayloadKey = "poster_size_preferences_payload"

    private var profilePreferences: SharedPreferences? = null
    private var localSizePreferences: SharedPreferences? = null
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        profilePreferences = context.getSharedPreferences(profilePreferencesName, Context.MODE_PRIVATE)
        localSizePreferences = context.getSharedPreferences(localSizePreferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadProfilePayload(): String? =
        profilePreferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun saveProfilePayload(payload: String) {
        profilePreferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    actual fun loadLocalSizePayload(): String? =
        localSizePreferences?.getString(localSizePayloadKey, null)

    actual fun saveLocalSizePayload(payload: String) {
        localSizePreferences
            ?.edit()
            ?.putString(localSizePayloadKey, payload)
            ?.apply()
    }

    fun isTabletFormFactor(): Boolean =
        applicationContext?.resources?.configuration?.smallestScreenWidthDp?.let { it >= 600 } == true
}

internal actual fun isTabletFormFactor(): Boolean = PosterCardStyleStorage.isTabletFormFactor()
