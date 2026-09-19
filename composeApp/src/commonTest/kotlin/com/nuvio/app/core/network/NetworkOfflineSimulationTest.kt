package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NetworkOfflineSimulationTest {
    @Test
    fun `offline simulation changes presentation without losing the actual generation`() {
        val actual = NetworkStatusUiState(
            condition = NetworkCondition.Online,
            probeGeneration = 42L,
            isProbing = true,
            keepOfflinePresentation = true,
        )

        val simulated = networkStatusWithOfflineSimulation(actual, simulationEnabled = true)

        assertEquals(NetworkCondition.NoInternet, simulated.condition)
        assertEquals(42L, simulated.probeGeneration)
        assertFalse(simulated.isProbing)
        assertFalse(simulated.keepOfflinePresentation)
        assertEquals(actual, networkStatusWithOfflineSimulation(actual, simulationEnabled = false))
    }
}
