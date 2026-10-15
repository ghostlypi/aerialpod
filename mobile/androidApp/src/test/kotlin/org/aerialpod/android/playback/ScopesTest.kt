package org.aerialpod.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopesTest {

    @Test
    fun cancellingTheMainScopeLeavesTheGraphScopeAlive() {
        // The service's onDestroy cancels its coordinator's main scope; that
        // must not take the app-wide scope (playback, theme, sync) with it.
        val graph = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val main = mainChildScope(graph)

        main.cancel()

        assertFalse(main.isActive)
        assertTrue(graph.isActive)
        graph.cancel()
    }

    @Test
    fun cancellingTheGraphScopeStillCancelsTheMainScope() {
        val graph = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val main = mainChildScope(graph)

        graph.cancel()

        assertFalse(main.isActive)
    }
}
