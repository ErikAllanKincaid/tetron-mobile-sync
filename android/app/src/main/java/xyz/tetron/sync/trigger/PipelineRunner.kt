// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import xyz.tetron.sync.pipeline.PipelineResult

/**
 * SYNC-006: the one seam every trigger source calls into. Production
 * wiring is `PipelineRunner { pipeline.run() }` (a direct adapter over
 * [xyz.tetron.sync.pipeline.SyncPipeline.run], SAM-converted); tests use a
 * fake that counts invocations. Kept separate from `SyncPipeline` itself
 * (rather than triggers depending on it directly) so trigger-layer tests
 * never need the bridge/target/source dependencies a real pipeline
 * requires.
 */
fun interface PipelineRunner {
    fun run(): PipelineResult
}
