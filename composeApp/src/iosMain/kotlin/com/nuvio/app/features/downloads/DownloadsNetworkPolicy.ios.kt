package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

internal actual object DownloadsNetworkPolicyStorage {
    private const val wifiOnlyKey = "nuvio.downloads.network.wifi_only"
    private const val allowCellularKey = "nuvio.downloads.network.allow_cellular"
    private const val allowExpensiveKey = "nuvio.downloads.network.allow_expensive"
    private const val allowConstrainedKey = "nuvio.downloads.network.allow_constrained"

    actual fun load(): DownloadNetworkPolicy? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(wifiOnlyKey) == null) return null
        return DownloadNetworkPolicy(
            wifiOnly = defaults.boolForKey(wifiOnlyKey),
            allowCellular = defaults.boolForKey(allowCellularKey),
            allowExpensiveNetworks = defaults.boolForKey(allowExpensiveKey),
            allowConstrainedNetworks = defaults.boolForKey(allowConstrainedKey),
        )
    }

    actual fun save(policy: DownloadNetworkPolicy) {
        NSUserDefaults.standardUserDefaults.apply {
            setBool(policy.wifiOnly, forKey = wifiOnlyKey)
            setBool(policy.allowCellular, forKey = allowCellularKey)
            setBool(policy.allowExpensiveNetworks, forKey = allowExpensiveKey)
            setBool(policy.allowConstrainedNetworks, forKey = allowConstrainedKey)
        }
    }
}
