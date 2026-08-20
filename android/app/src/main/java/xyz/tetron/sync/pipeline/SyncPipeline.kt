// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.util.concurrent.atomic.AtomicBoolean
import uniffi.tetron_mobile_sync.SyncException
import uniffi.tetron_mobile_sync.SyncProgressListener
import uniffi.tetron_mobile_sync.SyncRunOptions
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.bridge.MeshBridge
import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.delete.DeletionRequester
import xyz.tetron.sync.delete.TransferredFileCollector
import xyz.tetron.sync.delete.resolveDeleteSet
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.gates.GateDecision
import xyz.tetron.sync.gates.GateEvaluator
import xyz.tetron.sync.gates.GateInputs
import xyz.tetron.sync.gates.GateNotificationCoalescer
import xyz.tetron.sync.gates.GateReason
import xyz.tetron.sync.gates.relaxedGateConfig

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
 *
 * SYNC-007: a successful (including interrupted -- not a failure) run tees
 * [progress] through a [TransferredFileCollector] and, when [deleteConfig]
 * is opted in, hands the byte-verified transferred-this-run set to
 * [deletionRequester]. Default-constructed [deleteConfig]/[deletionRequester]
 * mean this is a no-op unless a caller opts in.
 *
 * SYNC-009: [run]'s `overrideReason` parameter is the Home screen's
 * "Transfer anyway?" confirm -- see [resolveOverride]/[relaxedGateConfig].
 * [gateConfig]/[deleteConfig] are suppliers, not fixed values, read fresh
 * on every [run] call -- a Settings-screen toggle must be reflected on the
 * very next run (SYNC-009 ACCEPTANCE), and this [SyncPipeline] instance is
 * a long-lived singleton in the app's composition root, constructed once
 * well before any particular settings value is known.
 */
class SyncPipeline(
    private val bridge: MeshBridge,
    private val targetProvider: TargetProvider,
    private val sourcePathProvider: SourcePathProvider,
    private val deviceState: DeviceStateProvider,
    private val transferRunner: TransferRunner,
    private val historyStore: RunHistoryStore,
    private val coalescer: GateNotificationCoalescer,
    private val gateConfig: () -> GateConfig = { GateConfig() },
    private val runOptions: SyncRunOptions = DEFAULT_RUN_OPTIONS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onNotify: (GateReason) -> Unit = {},
    private val deleteConfig: () -> DeleteAfterBackupConfig = { DeleteAfterBackupConfig() },
    private val deletionRequester: DeletionRequester = DeletionRequester {},
) {
    private val running = AtomicBoolean(false)

    /** Synchronous and blocking (network I/O on the calling thread, same
     *  contract as [uniffi.tetron_mobile_sync.SyncEngine.runClient]) --
     *  callers must invoke this off the main thread.
     *
     *  [overrideReason] is SYNC-009's "Transfer anyway?" confirm: when the
     *  AND-matrix blocks on exactly this reason, the run proceeds with
     *  that one gate's knob relaxed ([relaxedGateConfig]) instead of being
     *  gated. It has no effect when the block reason differs (including a
     *  reason with no relaxable knob) or when nothing is blocking. */
    fun run(progress: SyncProgressListener? = null, overrideReason: GateReason? = null): PipelineResult {
        if (!running.compareAndSet(false, true)) return PipelineResult.AlreadyRunning
        try {
            return runOnce(progress, overrideReason)
        } finally {
            running.set(false)
        }
    }

    private fun runOnce(progress: SyncProgressListener?, overrideReason: GateReason?): PipelineResult {
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

        val currentGateConfig = gateConfig()
        when (val decision = GateEvaluator.evaluate(inputs, currentGateConfig)) {
            is GateDecision.Blocked -> {
                val overridden = resolveOverride(decision, inputs, currentGateConfig, overrideReason)
                if (overridden is GateDecision.Blocked) return gated(overridden.reason)
            }
            GateDecision.Allowed -> Unit
        }

        // Gates passed, but the direct-only gate only constrains ConnKind
        // *when a target resolved* -- disabling it (or an unresolved
        // target's IP not being in the roster while charging/battery/wifi
        // still open) can reach here with no usable destination.
        if (target == null) return gated(GateReason.TargetUnreachable)
        val source = sourcePathProvider.sourcePath() ?: return gated(GateReason.TargetUnreachable)

        val destination = "rsync://${target.meshIp}:${target.port}/${target.module}/"
        val collector = TransferredFileCollector(progress)
        val record = try {
            val outcome = transferRunner.run(source, destination, runOptions, collector)
            val added = outcome.filesCopied.toInt()
            val total = outcome.filesTotal.toInt()
            // SYNC-007: interrupted is explicitly NOT a failure (below), so
            // whatever this run did byte-verify stays eligible for deletion
            // -- only a hard engine exception (the catch branch) withholds it.
            requestDeleteIfEnabled(collector.transferredPaths())
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

    /** SYNC-009: applies [overrideReason] only when it names the exact
     *  reason [blocked] blocked on this cycle -- a stale/mismatched
     *  override (e.g. the device dropped Wi-Fi AND went low-battery
     *  between the confirm dialog and this call) is rejected, since only
     *  whichever reason actually blocks now is meaningful. Returns
     *  [GateDecision.Allowed] when the relaxed config now passes,
     *  otherwise the (possibly different) still-blocking decision. */
    private fun resolveOverride(
        blocked: GateDecision.Blocked,
        inputs: GateInputs,
        currentGateConfig: GateConfig,
        overrideReason: GateReason?,
    ): GateDecision {
        if (overrideReason != blocked.reason) return blocked
        val relaxed = relaxedGateConfig(blocked.reason, currentGateConfig) ?: return blocked
        return GateEvaluator.evaluate(inputs, relaxed)
    }

    /** SYNC-007: never touches [deletionRequester] when the opt-in is off or
     *  nothing byte-verified transferred this run -- [resolveDeleteSet]
     *  carries that gate so it stays testable independent of the pipeline. */
    private fun requestDeleteIfEnabled(transferredPaths: List<String>) {
        val deleteSet = resolveDeleteSet(deleteConfig(), transferredPaths)
        if (deleteSet.isNotEmpty()) deletionRequester.requestDelete(deleteSet)
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
