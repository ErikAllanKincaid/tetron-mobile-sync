// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * SYNC-006: schedules [SyncWorker] as a periodic job. Cadence default is
 * ~daily (spec/sync.py SYNC-006, provisional -- "the OS runs it when
 * convenient, typically overnight"); the `CONNECTED` constraint is a
 * coarse pre-filter only, not the gate decision itself -- SYNC-004's gates
 * (Wi-Fi/direct/battery/charging) still run inside the pipeline every time
 * the job fires, since WorkManager's own network constraint can not express
 * "Wi-Fi only" (`UNMETERED` is a weaker proxy tetron already rejected via
 * decision #7/#11 -- no SSID/network-type heuristics for the gate itself).
 */
object SyncWorkScheduler {
    const val UNIQUE_WORK_NAME = "xyz.tetron.sync.periodic-backup"
    val DEFAULT_INTERVAL: Duration = Duration.ofDays(1)

    fun buildPeriodicRequest(interval: Duration = DEFAULT_INTERVAL): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(interval)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

    /** [ExistingPeriodicWorkPolicy.KEEP]: re-launching the app must not
     *  reset an already-scheduled job's timer. */
    fun schedule(workManager: WorkManager, interval: Duration = DEFAULT_INTERVAL) {
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildPeriodicRequest(interval),
        )
    }
}
