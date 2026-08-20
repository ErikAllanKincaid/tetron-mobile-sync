// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import uniffi.tetron_mobile_sync.SyncEngineInterface
import uniffi.tetron_mobile_sync.SyncProgressListener
import uniffi.tetron_mobile_sync.SyncRunOptions
import uniffi.tetron_mobile_sync.SyncTransferOutcome

/** SYNC-005: the production [TransferRunner] -- a direct call through to the
 *  SYNC-002 UniFFI engine. No logic of its own, so no unit test (same bar
 *  as [xyz.tetron.sync.bridge.ProviderStatusCaller]); [SyncPipeline]'s tests
 *  exercise everything through a fake [TransferRunner] instead. */
class EngineTransferRunner(private val engine: SyncEngineInterface) : TransferRunner {
    override fun run(
        source: String,
        destination: String,
        options: SyncRunOptions,
        progress: SyncProgressListener?,
    ): SyncTransferOutcome = engine.runClient(source, destination, options, progress)
}
