// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.tetron_mobile_sync.SyncProgressEvent
import uniffi.tetron_mobile_sync.SyncProgressListener
import xyz.tetron.sync.AppContainer
import xyz.tetron.sync.bridge.BridgePeer
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.gates.GateReason
import xyz.tetron.sync.gates.relaxedGateConfig
import xyz.tetron.sync.media.MediaAccessGrant
import xyz.tetron.sync.pipeline.PipelineResult
import xyz.tetron.sync.pipeline.RunRecord
import xyz.tetron.sync.pipeline.SyncTarget

/** The Home screen's "big button" state -- also what the Progress screen
 *  reads (same [HomeViewModel] instance, Activity-scoped, since a run
 *  started from Home must be observable from Progress). */
sealed class RunPhase {
    data object Idle : RunPhase()

    /** [progress] is the raw last engine event (kept for the Home
     *  screen's one-line summary); [detail] is the aggregated view the
     *  Progress screen renders (rate, ETA, live file list).
     *  [startedAtMillis] is wall-clock at run start -- used to recognise a
     *  run's completion in the history store even when it was started by
     *  another trigger (periodic/network-change) and this ViewModel's own
     *  `pipeline.run` call therefore returned [PipelineResult.AlreadyRunning]. */
    data class Running(
        val progress: SyncProgressEvent? = null,
        val detail: RunProgressSnapshot? = null,
        val startedAtMillis: Long = 0,
    ) : RunPhase()

    /** [canOverride] mirrors [relaxedGateConfig] returning non-null --
     *  drives whether Home offers "Transfer anyway?" at all. */
    data class Gated(val reason: GateReason, val canOverride: Boolean) : RunPhase()
    data class Finished(val record: RunRecord) : RunPhase()
}

data class HomeUiState(
    val tunnelState: BridgeTunnelState = BridgeTunnelState.Unknown,
    val consentCallerPackage: String? = null,
    val target: SyncTarget? = null,
    val rosterPeers: List<BridgePeer> = emptyList(),
    val mediaAccessGrant: MediaAccessGrant = MediaAccessGrant.NotGranted,
    val runPhase: RunPhase = RunPhase.Idle,
    /** Most recent recorded run, polled from the history store -- lets the
     *  screen fall out of [RunPhase.Running] when a run started elsewhere
     *  (periodic/network-change trigger) finishes. */
    val lastRun: RunRecord? = null,
)

/**
 * SYNC-009: owns the one in-flight run at a time (mirrors [xyz.tetron.sync
 * .pipeline.SyncPipeline]'s own single-run reentrancy at the UI layer --
 * [backUpNow]/[transferAnyway] are no-ops while already [RunPhase.Running])
 * and polls the bridge/target/media-access state for display. Polling
 * (not a push subscription) matches [xyz.tetron.sync.bridge.MeshBridge]'s
 * own design: it is a short-TTL cache over a provider that itself only
 * refreshes on its own tick, so there is no live stream to subscribe to.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val runPhase = MutableStateFlow<RunPhase>(RunPhase.Idle)

    /** A failing [pollNow] (e.g. the cross-process bridge Binder call
     *  throwing while tetron-mobile is saturated mid-transfer) must never
     *  terminate this flow -- doing so would freeze [uiState] on its last
     *  value for the rest of the process, so a run could complete with the
     *  screen stuck on "Backing up…". On failure keep emitting the last
     *  good snapshot and try again next tick. */
    private val polled = flow {
        var last = HomeUiState()
        while (true) {
            last = runCatching { pollNow() }.getOrDefault(last)
            emit(last)
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<HomeUiState> =
        combine(polled, runPhase) { polled, phase ->
            // If we believe a run is in progress but the history store has
            // recorded a run that started at/after this one, that run has
            // finished (possibly one started by another trigger while our
            // own `pipeline.run` call got AlreadyRunning) -- surface it so
            // the screen never wedges on "Backing up…".
            val effective = if (
                phase is RunPhase.Running &&
                phase.startedAtMillis > 0 &&
                polled.lastRun != null &&
                polled.lastRun.timestampMillis >= phase.startedAtMillis
            ) {
                RunPhase.Finished(polled.lastRun)
            } else {
                phase
            }
            polled.copy(runPhase = effective)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun backUpNow() = runInternal(overrideReason = null)

    fun transferAnyway(reason: GateReason) = runInternal(overrideReason = reason)

    /** TODO #8: a no-op unless a run is actually in progress -- guards
     *  against a stray tap after the run just finished (the button that
     *  triggers this is only shown while [RunPhase.Running] anyway, but the
     *  check is cheap insurance against the race between that finishing and
     *  the tap landing). [container.syncEngine]'s `cancel()` is a
     *  process-global request (mirrors real Ctrl+C, see the Rust doc on
     *  `SyncEngine.cancel`), so it reaches whichever run is actually in
     *  flight regardless of which trigger (manual/periodic/network-change)
     *  started it. */
    fun cancel() {
        if (runPhase.value is RunPhase.Running) container.syncEngine.cancel()
    }

    private fun runInternal(overrideReason: GateReason?) {
        if (runPhase.value is RunPhase.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            // Best-effort whole-run denominator for the byte progress bar +
            // ETA. The engine's daemon path never reports an overall total
            // (spec/sync.py SYNC-009 Progress), so use the in-scope backlog
            // size -- close to the real transfer on a first backup, an
            // over-estimate on a mostly-idempotent re-run (bar just fills
            // fast then). `0` -> the Progress screen falls back to the
            // file-count fraction.
            val totalBytes = runCatching {
                container.mediaAccess.backlogEstimate(container.settingsStore.backupScope()).includedBytes
            }.getOrDefault(0L)
            val startedAt = System.currentTimeMillis()
            val tracker = RunProgressTracker(totalBytes)
            runPhase.value = RunPhase.Running(detail = tracker.snapshot(), startedAtMillis = startedAt)
            // The pipeline already turns a SyncException into a failed
            // RunRecord; this guard is only for an unexpected throw (an FFI
            // surprise, an observer callback fault) so a run can never leave
            // the UI wedged on RunPhase.Running with no way out.
            val phase = try {
                when (val result = container.pipeline.run(
                    progress = LiveProgressListener(tracker, startedAt),
                    overrideReason = overrideReason,
                )) {
                    is PipelineResult.Ran -> RunPhase.Finished(result.record)
                    is PipelineResult.Gated -> RunPhase.Gated(
                        reason = result.reason,
                        canOverride = relaxedGateConfig(result.reason, container.settingsStore.gateConfig()) != null,
                    )
                    // Another trigger owns the run; the history-store poll in
                    // [uiState] will surface its completion.
                    PipelineResult.AlreadyRunning -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "backup run failed unexpectedly", e)
                RunPhase.Finished(
                    RunRecord(
                        timestampMillis = System.currentTimeMillis(),
                        added = 0,
                        skipped = 0,
                        failed = 1,
                        interrupted = false,
                        failureReason = e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
            phase?.let { runPhase.value = it }
        }
    }

    private fun pollNow(): HomeUiState {
        val response = container.bridge.current()
        val snapshot = (response as? BridgeResponse.Snapshot)?.snapshot
        val tunnelState = snapshot?.state ?: BridgeTunnelState.Unknown
        val consentCallerPackage = (response as? BridgeResponse.ConsentRequired)?.callerPackage
        return HomeUiState(
            tunnelState = tunnelState,
            consentCallerPackage = consentCallerPackage,
            target = container.settingsStore.target(),
            rosterPeers = snapshot?.peers ?: emptyList(),
            mediaAccessGrant = container.mediaAccess.currentState().grant,
            lastRun = container.historyStore.lastRun(),
        )
    }

    /** The backup target is edited directly from Home now (just the mesh
     *  peer -- module name and port are Settings > Connection). Written on
     *  IO: the first save on a fresh install is where the store seeds the
     *  device label, which reads the mesh hostname from the bridge cache. */
    fun setTarget(target: SyncTarget?) {
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.setTarget(target)
        }
    }

    private inner class LiveProgressListener(
        private val tracker: RunProgressTracker,
        private val startedAtMillis: Long,
    ) : SyncProgressListener {
        override fun onProgress(event: SyncProgressEvent) {
            runPhase.value = RunPhase.Running(
                progress = event,
                detail = tracker.onEvent(event),
                startedAtMillis = startedAtMillis,
            )
        }
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
