// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * SYNC-006: injects [runner] into [SyncWorker] at construction time
 * (WorkManager's default factory only supports a reflective
 * `(Context, WorkerParameters)` constructor, which cannot carry a real
 * dependency without a global). Callers register this once via
 * `WorkManager.initialize` / `Configuration.Builder.setWorkerFactory` --
 * production app-startup wiring is a follow-up alongside SYNC-008/009's
 * own DI, same "wiring is minimal" note as elsewhere in this file's specs.
 */
class SyncWorkerFactory(private val runner: PipelineRunner) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) {
            SyncWorker(appContext, workerParameters, runner)
        } else {
            null
        }
}
