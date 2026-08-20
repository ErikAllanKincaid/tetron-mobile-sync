// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.tetron.sync.gates.GateReason
import xyz.tetron.sync.pipeline.PipelineResult

/**
 * SYNC-006 ACCEPTANCE: the manual trigger invokes the pipeline entry
 * exactly once per call and passes its result through unchanged --
 * including [PipelineResult.AlreadyRunning], the mid-run-trigger case
 * (SYNC-005 already makes it a no-op; this only proves the trigger layer
 * does not do anything extra on top).
 */
class ManualTriggerTest {

    @Test
    fun triggerNow_invokesRunnerExactlyOnce() {
        val calls = AtomicInteger(0)
        val runner = PipelineRunner {
            calls.incrementAndGet()
            PipelineResult.Gated(GateReason.NotOnWifi)
        }
        val trigger = ManualTrigger(runner)

        trigger.triggerNow()

        assertEquals(1, calls.get())
    }

    @Test
    fun triggerNow_passesResultThroughUnchanged() {
        val runner = PipelineRunner { PipelineResult.AlreadyRunning }
        val trigger = ManualTrigger(runner)

        assertEquals(PipelineResult.AlreadyRunning, trigger.triggerNow())
    }
}
