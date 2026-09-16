package com.nuvio.app

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTabsDestinationTest {

    @Test
    fun `offline status appears on root routes for connection failures`() {
        assertTrue(shouldShowRootOfflineStatus(true, NetworkCondition.NoInternet))
        assertTrue(shouldShowRootOfflineStatus(true, NetworkCondition.ServersUnreachable))
    }

    @Test
    fun `offline status stays hidden outside root routes and while checking`() {
        assertFalse(shouldShowRootOfflineStatus(false, NetworkCondition.NoInternet))
        assertFalse(shouldShowRootOfflineStatus(true, NetworkCondition.Online))
        assertFalse(shouldShowRootOfflineStatus(true, NetworkCondition.Checking))
    }
}
