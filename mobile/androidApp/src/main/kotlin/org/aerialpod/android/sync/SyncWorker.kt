package org.aerialpod.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aerialpod.android.appGraph

/**
 * The periodic background pass.
 *
 * Deliberately thin: every decision it could get wrong lives in
 * [org.aerialpod.core.AerialPodCore.backgroundSync], where it is testable
 * without an Android runtime. All this class contributes is WorkManager's
 * vocabulary for "try again later".
 *
 * It does **not** touch the LAN peer mesh. That is foreground-scoped on
 * purpose — `LanPeerService` replaced its timers with path callbacks and
 * reconnection — so a worker dialling peers would fight `PeerLifecycle` for
 * ownership of a service the user cannot see running.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = applicationContext.appGraph.core.backgroundSync()
        // Feed failures do not retry: one unreachable feed is normal, and the
        // next scheduled pass is soon enough. Only a sync that actually failed
        // — as opposed to one there is no account for — is worth a backoff.
        if (result.shouldRetry) Result.retry() else Result.success()
    }
}
