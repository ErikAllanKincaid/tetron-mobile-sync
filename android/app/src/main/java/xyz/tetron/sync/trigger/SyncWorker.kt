// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.Worker

/**
 * SYNC-006: the periodic WorkManager job (decision #14, "opportunistic v1
 * ... periodic WorkManager job -- cadence ... ~daily default"). No logic
 * of its own -- [performSyncWork] carries the (unit-tested) behavior; this
 * class only exists because WorkManager needs a real `Worker` to enqueue.
 * Constructed by [SyncWorkerFactory], not WorkManager's default reflective
 * factory, so [runner] can be a real dependency rather than a global.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: PipelineRunner,
) : Worker(context, params) {
    override fun doWork(): Result = performSyncWork(runner)
}
