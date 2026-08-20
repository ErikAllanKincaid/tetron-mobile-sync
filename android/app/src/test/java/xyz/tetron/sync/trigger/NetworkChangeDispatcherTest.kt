// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.tetron.sync.pipeline.PipelineResult

/**
 * SYNC-006 ACCEPTANCE: a network-available event invokes the pipeline
 * entry exactly once, off the calling thread (never inline on the Binder
 * callback thread, since the runner blocks on network I/O).
 */
class NetworkChangeDispatcherTest {

    @Test
    fun onNetworkAvailable_invokesRunnerExactlyOnce() {
        val calls = AtomicInteger(0)
        val runner = PipelineRunner {
            calls.incrementAndGet()
            PipelineResult.AlreadyRunning
        }
        val dispatcher = NetworkChangeDispatcher(runner, executor = Executor { it.run() })

        dispatcher.onNetworkAvailable()

        assertEquals(1, calls.get())
    }

    @Test
    fun onNetworkAvailable_dispatchesThroughTheExecutor_notInline() {
        var calls = 0
        val runner = PipelineRunner { calls++; PipelineResult.AlreadyRunning }
        val pending = mutableListOf<Runnable>()
        val queueingExecutor = Executor { pending.add(it) }
        val dispatcher = NetworkChangeDispatcher(runner, queueingExecutor)

        dispatcher.onNetworkAvailable()
        assertEquals("must be submitted to the executor, not run on the calling thread", 0, calls)

        pending.single().run()
        assertTrue(calls == 1)
    }
}
