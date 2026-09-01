package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

internal actual object DownloadsNetworkPolicyStorage {
    private const val preferencesName = "nuvio_download_network_policy"
    private const val wifiOnlyKey = "wifi_only"
    private const val allowCellularKey = "allow_cellular"
    private const val allowExpensiveKey = "allow_expensive"
    private const val allowConstrainedKey = "allow_constrained"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(): DownloadNetworkPolicy? {
        val stored = preferences ?: return null
        if (!stored.contains(wifiOnlyKey)) return null
        return DownloadNetworkPolicy(
            wifiOnly = stored.getBoolean(wifiOnlyKey, false),
            allowCellular = stored.getBoolean(allowCellularKey, true),
            allowExpensiveNetworks = stored.getBoolean(allowExpensiveKey, true),
            allowConstrainedNetworks = stored.getBoolean(allowConstrainedKey, true),
        )
    }

    actual fun save(policy: DownloadNetworkPolicy) {
        preferences
            ?.edit()
            ?.putBoolean(wifiOnlyKey, policy.wifiOnly)
            ?.putBoolean(allowCellularKey, policy.allowCellular)
            ?.putBoolean(allowExpensiveKey, policy.allowExpensiveNetworks)
            ?.putBoolean(allowConstrainedKey, policy.allowConstrainedNetworks)
            ?.apply()
    }
}
