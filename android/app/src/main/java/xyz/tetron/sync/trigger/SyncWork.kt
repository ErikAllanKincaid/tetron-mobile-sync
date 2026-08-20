// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import androidx.work.ListenableWorker
import xyz.tetron.sync.pipeline.PipelineResult

/**
 * SYNC-006: what firing the periodic trigger actually does, kept separate
 * from `SyncWorker`/`WorkerParameters` (whose constructor is not directly
 * instantiable outside WorkManager's own factories) so it is JVM-unit-
 * testable. [SyncWorker.doWork] just calls this. A run that failed hard
 * gets `retry()` (WorkManager's own backoff policy); a gated or
 * already-running cycle is `success()` -- neither is an error state at
 * this layer (SYNC-004 already coalesced the gated notification; SYNC-005
 * reentrancy already made the mid-run trigger a no-op).
 */
fun performSyncWork(runner: PipelineRunner): ListenableWorker.Result =
    when (val result = runner.run()) {
        is PipelineResult.Ran ->
            if (result.record.failed > 0) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success()
            }
        PipelineResult.AlreadyRunning -> ListenableWorker.Result.success()
        is PipelineResult.Gated -> ListenableWorker.Result.success()
    }
