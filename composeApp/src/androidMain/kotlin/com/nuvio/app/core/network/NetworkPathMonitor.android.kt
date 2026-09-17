package com.nuvio.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal actual object NetworkPathMonitor {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun events(): Flow<NetworkPathEvent> = callbackFlow {
        val connectivity = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity == null) {
            trySend(NetworkPathEvent.Unavailable)
            close()
            return@callbackFlow
        }
        fun currentEvent(): NetworkPathEvent {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            return networkPathEventForCapabilities(
                hasInternetCapability = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ) == true,
                hasValidatedCapability = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ) == true,
            )
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = Unit

            override fun onLost(network: Network) {
                trySend(networkPathEventAfterLoss())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(
                    networkPathEventForCapabilities(
                        hasInternetCapability = capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        ),
                        hasValidatedCapability = capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                        ),
                    ),
                )
            }
        }
        trySend(currentEvent())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
