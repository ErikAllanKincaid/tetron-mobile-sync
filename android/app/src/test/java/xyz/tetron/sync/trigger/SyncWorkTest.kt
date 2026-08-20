// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import androidx.work.ListenableWorker
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.tetron.sync.gates.GateReason
import xyz.tetron.sync.pipeline.PipelineResult
import xyz.tetron.sync.pipeline.RunRecord

/**
 * SYNC-006 ACCEPTANCE: the periodic trigger invokes the pipeline entry
 * exactly once per firing and maps every [PipelineResult] to the right
 * WorkManager outcome -- a hard failure retries (WorkManager's own
 * backoff), everything else (including the mid-run no-op) succeeds, since
 * neither gating nor reentrancy is itself an error at this layer.
 */
class SyncWorkTest {

    private fun record(failed: Int) = RunRecord(
        timestampMillis = 0L,
        added = 0,
        skipped = 0,
        failed = failed,
        interrupted = false,
        failureReason = if (failed > 0) "boom" else null,
    )

    @Test
    fun invokesRunnerExactlyOnce() {
        val calls = AtomicInteger(0)
        val runner = PipelineRunner { calls.incrementAndGet(); PipelineResult.Ran(record(failed = 0)) }

        performSyncWork(runner)

        assertEquals(1, calls.get())
    }

    @Test
    fun successfulRun_mapsToSuccess() {
        val runner = PipelineRunner { PipelineResult.Ran(record(failed = 0)) }
        assertEquals(ListenableWorker.Result.success(), performSyncWork(runner))
    }

    @Test
    fun hardFailure_mapsToRetry() {
        val runner = PipelineRunner { PipelineResult.Ran(record(failed = 1)) }
        assertEquals(ListenableWorker.Result.retry(), performSyncWork(runner))
    }

    @Test
    fun gatedCycle_mapsToSuccess_notAnError() {
        val runner = PipelineRunner { PipelineResult.Gated(GateReason.LowBattery) }
        assertEquals(ListenableWorker.Result.success(), performSyncWork(runner))
    }

    @Test
    fun alreadyRunning_mapsToSuccess_theMidRunNoOp() {
        val runner = PipelineRunner { PipelineResult.AlreadyRunning }
        assertEquals(ListenableWorker.Result.success(), performSyncWork(runner))
    }
}
