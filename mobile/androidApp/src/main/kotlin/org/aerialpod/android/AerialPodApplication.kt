package org.aerialpod.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper

class AerialPodApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.warmUp()
    }
}

/**
 * The graph, from any Context.
 *
 * `applicationContext` rather than walking up the wrapper chain, so this is
 * still correct when called from a Service or a BroadcastReceiver — which is
 * where step 6.2's player and 6.4's network callback will ask for it.
 */
val Context.appGraph: AppGraph
    get() {
        val app = applicationContext as? AerialPodApplication
            ?: (this as? ContextWrapper)?.baseContext?.applicationContext as? AerialPodApplication
            ?: error("AerialPodApplication is not the running Application")
        return app.graph
    }
