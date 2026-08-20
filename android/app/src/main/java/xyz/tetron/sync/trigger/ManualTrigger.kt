// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import xyz.tetron.sync.pipeline.PipelineResult

/**
 * SYNC-006: the "Back up now" entry point (UI wiring -- the button itself
 * -- is SYNC-009). Blocking: same off-main-thread contract as
 * [xyz.tetron.sync.pipeline.SyncPipeline.run]; SYNC-009 calls this from a
 * background dispatcher the same way `MainActivity`'s scaffold screen
 * already calls `SyncEngine.version()`.
 */
class ManualTrigger(private val runner: PipelineRunner) {
    fun triggerNow(): PipelineResult = runner.run()
}
