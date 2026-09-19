package com.nuvio.app.features.watchtogether.hosted

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object WatchTogetherHostedSessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session = WatchTogetherHostedSession(scope)

    init {
        scope.launch { session.restoreInstalledService() }
    }
}
