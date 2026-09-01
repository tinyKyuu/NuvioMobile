package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadNetworkPolicy(
    val wifiOnly: Boolean = false,
    val allowCellular: Boolean = true,
    val allowExpensiveNetworks: Boolean = true,
    val allowConstrainedNetworks: Boolean = true,
) {
    val effectiveAllowsCellular: Boolean
        get() = allowCellular && !wifiOnly
}

internal expect object DownloadsNetworkPolicyStorage {
    fun load(): DownloadNetworkPolicy?

    fun save(policy: DownloadNetworkPolicy)
}

object DownloadsNetworkPolicyRepository {
    private val _policy = MutableStateFlow(
        DownloadsNetworkPolicyStorage.load() ?: DownloadNetworkPolicy(),
    )
    val policy: StateFlow<DownloadNetworkPolicy> = _policy.asStateFlow()

    fun update(policy: DownloadNetworkPolicy) {
        if (_policy.value == policy) return
        DownloadsNetworkPolicyStorage.save(policy)
        _policy.value = policy
    }
}
