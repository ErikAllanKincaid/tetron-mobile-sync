// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.util.concurrent.atomic.AtomicBoolean
import uniffi.tetron_mobile_sync.SyncException
import uniffi.tetron_mobile_sync.SyncProgressListener
import uniffi.tetron_mobile_sync.SyncRunOptions
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.bridge.MeshBridge
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.gates.GateDecision
import xyz.tetron.sync.gates.GateEvaluator
import xyz.tetron.sync.gates.GateInputs
import xyz.tetron.sync.gates.GateNotificationCoalescer
import xyz.tetron.sync.gates.GateReason

/**
 * SYNC-005: the single run path every trigger (SYNC-006) funnels into.
 * Evaluate gates (SYNC-004) -> resolve the target + source -> invoke the
 * engine (SYNC-002) -> record history -> notify (coalesced). Reentrant
 * calls while a run is in progress are a no-op ([PipelineResult.AlreadyRunning]),
 * which also covers WorkManager re-entry after process death: the pipeline
 * itself holds no state between calls, only [historyStore] does.
 *
 * [onNotify] is called at most once per gated cycle, only when
 * [coalescer] has not already notified for that reason within its window;
 * it does not post the actual notification (SYNC-009 owns channels/copy),
 * it only decides *when*.
 */
class SyncPipeline(
    private val bridge: MeshBridge,
    private val targetProvider: TargetProvider,
    private val sourcePathProvider: SourcePathProvider,
    private val deviceState: DeviceStateProvider,
    private val transferRunner: TransferRunner,
    private val historyStore: RunHistoryStore,
    private val coalescer: GateNotificationCoalescer,
    private val gateConfig: GateConfig = GateConfig(),
    private val runOptions: SyncRunOptions = DEFAULT_RUN_OPTIONS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onNotify: (GateReason) -> Unit = {},
) {
    private val running = AtomicBoolean(false)

    /** Synchronous and blocking (network I/O on the calling thread, same
     *  contract as [uniffi.tetron_mobile_sync.SyncEngine.runClient]) --
     *  callers must invoke this off the main thread. */
    fun run(progress: SyncProgressListener? = null): PipelineResult {
        if (!running.compareAndSet(false, true)) return PipelineResult.AlreadyRunning
        try {
            return runOnce(progress)
        } finally {
            running.set(false)
        }
    }

    private fun runOnce(progress: SyncProgressListener?): PipelineResult {
        val snapshot = (bridge.current() as? BridgeResponse.Snapshot)?.snapshot
        val target = targetProvider.currentTarget()
        val targetConnKind = target?.let { t -> snapshot?.peers?.firstOrNull { it.ip == t.meshIp }?.connKind }

        val inputs = GateInputs(
            tunnelState = snapshot?.state ?: BridgeTunnelState.Unknown,
            isWifiConnected = deviceState.isWifiConnected(),
            isCharging = deviceState.isCharging(),
            batteryPercent = deviceState.batteryPercent(),
            targetConnKind = targetConnKind,
        )

        when (val decision = GateEvaluator.evaluate(inputs, gateConfig)) {
            is GateDecision.Blocked -> return gated(decision.reason)
            GateDecision.Allowed -> Unit
        }

        // Gates passed, but the direct-only gate only constrains ConnKind
        // *when a target resolved* -- disabling it (or an unresolved
        // target's IP not being in the roster while charging/battery/wifi
        // still open) can reach here with no usable destination.
        if (target == null) return gated(GateReason.TargetUnreachable)
        val source = sourcePathProvider.sourcePath() ?: return gated(GateReason.TargetUnreachable)

        val destination = "rsync://${target.meshIp}:${target.port}/${target.module}/"
        val record = try {
            val outcome = transferRunner.run(source, destination, runOptions, progress)
            val added = outcome.filesCopied.toInt()
            val total = outcome.filesTotal.toInt()
            RunRecord(
                timestampMillis = nowMillis(),
                added = added,
                skipped = (total - added).coerceAtLeast(0),
                failed = 0,
                interrupted = outcome.ioErrorExitCode != null,
                failureReason = null,
            )
        } catch (e: SyncException) {
            RunRecord(
                timestampMillis = nowMillis(),
                added = 0,
                skipped = 0,
                failed = 1,
                interrupted = false,
                failureReason = e.message,
            )
        }
        historyStore.recordRun(record)
        return PipelineResult.Ran(record)
    }

    private fun gated(reason: GateReason): PipelineResult.Gated {
        if (coalescer.shouldNotify(reason)) onNotify(reason)
        return PipelineResult.Gated(reason)
    }

    companion object {
        /** `-rtl`, no bwlimit/modify-window override -- SYNC-009 exposes
         *  these as settings later; `--partial` is always on inside the
         *  engine regardless (SYNC-002 semantics). */
        val DEFAULT_RUN_OPTIONS = SyncRunOptions(
            recursive = true,
            times = true,
            links = true,
            modifyWindowSecs = null,
            bwlimitKib = null,
        )
    }
}
