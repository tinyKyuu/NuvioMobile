package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

internal actual object DownloadsRequestStorage {
    private const val preferencesName = "nuvio_download_requests"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(downloadId: String): String? =
        preferences?.getString(downloadId, null)

    actual fun savePayload(downloadId: String, payload: String): Boolean =
        preferences
            ?.edit()
            ?.putString(downloadId, payload)
            ?.commit()
            ?: false

    actual fun remove(downloadId: String) {
        preferences
            ?.edit()
            ?.remove(downloadId)
            ?.commit()
    }
}
