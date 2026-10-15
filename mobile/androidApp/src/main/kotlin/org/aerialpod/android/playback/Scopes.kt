package org.aerialpod.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * A main-thread scope that is a *child* of [parent], so cancelling it stops
 * only its own work. `parent.coroutineContext + Dispatchers.Main` would share
 * the parent's Job, and cancelling it would kill the app-wide graph scope —
 * which silently broke playback and settings after Android Auto unbound the
 * service while the process lived on.
 */
internal fun mainChildScope(parent: CoroutineScope): CoroutineScope =
    CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Main.immediate,
    )
