package org.aerialpod.android.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Ask for a download pass.
 *
 * WorkManager rather than a coroutine on the app's scope, because the whole
 * point of downloading ahead is that the episode is ready *later* — queued
 * before leaving the house, wanted on a train with no signal. A transfer tied
 * to the app's process would stop the moment the user swiped it away, which is
 * exactly when they thought it was safe to.
 *
 * `KEEP` rather than `REPLACE`: a queue edit during a transfer should not
 * cancel and restart it. The running pass re-reads the policy when it finishes
 * each file, so it already picks up the change.
 */
object Downloads {

    const val WORK_NAME = "aerialpod-downloads"

    fun request(context: Context) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    // Any connection, not unmetered-only: the setting the user
                    // controls is how many episodes to keep, and silently
                    // refusing to honour it on mobile data would make that
                    // setting a lie. Bandwidth is the user's call.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work)
    }
}
