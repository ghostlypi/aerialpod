package org.aerialpod.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.aerialpod.core.AerialPodCore
import java.util.concurrent.TimeUnit

/**
 * Keep the library moving while the app is not open.
 *
 * Without this the app syncs only on events it is awake for — a queue edit, a
 * final position report, the manual button — so a phone left alone never picks
 * up a new episode, and `AerialPodCore`'s note that the periodic schedule is
 * "the platform's job (WorkManager on Android)" described something that did
 * not exist.
 */
object BackgroundSync {

    const val WORK_NAME = "aerialpod-periodic-sync"

    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(
            AerialPodCore.BACKGROUND_INTERVAL_SECONDS, TimeUnit.SECONDS,
        ).setConstraints(
            Constraints.Builder()
                // Feed XML and a sync request are small, and the user's reason
                // for opening the app is to find the new episode already there.
                // Same call as the download policy: bandwidth is the user's.
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE, not KEEP: with KEEP a changed interval would never reach
            // anyone who already has the app installed, and the schedule that
            // matters is the one on the device, not the one in the source.
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }
}
