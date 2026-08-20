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
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.gates.GateReason
import xyz.tetron.sync.gates.relaxedGateConfig
import xyz.tetron.sync.pipeline.PipelineResult
import xyz.tetron.sync.pipeline.RunRecord

/** The Home screen's "big button" state -- also what the Progress screen
 *  reads (same [HomeViewModel] instance, Activity-scoped, since a run
 *  started from Home must be observable from Progress). */
sealed class RunPhase {
    data object Idle : RunPhase()
    data class Running(val progress: SyncProgressEvent?) : RunPhase()

    /** [canOverride] mirrors [relaxedGateConfig] returning non-null --
     *  drives whether Home offers "Transfer anyway?" at all. */
    data class Gated(val reason: GateReason, val canOverride: Boolean) : RunPhase()
    data class Finished(val record: RunRecord) : RunPhase()
}

data class HomeUiState(
    val tunnelState: BridgeTunnelState = BridgeTunnelState.Unknown,
    val consentCallerPackage: String? = null,
    val targetDisplayName: String? = null,
    val showPartialMediaAccessWarning: Boolean = false,
    val runPhase: RunPhase = RunPhase.Idle,
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

    private val polled = flow {
        while (true) {
            emit(pollNow())
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<HomeUiState> =
        combine(polled, runPhase) { polled, phase -> polled.copy(runPhase = phase) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun backUpNow() = runInternal(overrideReason = null)

    fun transferAnyway(reason: GateReason) = runInternal(overrideReason = reason)

    private fun runInternal(overrideReason: GateReason?) {
        if (runPhase.value is RunPhase.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            runPhase.value = RunPhase.Running(progress = null)
            when (val result = container.pipeline.run(progress = LiveProgressListener(), overrideReason = overrideReason)) {
                is PipelineResult.Ran -> runPhase.value = RunPhase.Finished(result.record)
                is PipelineResult.Gated -> runPhase.value = RunPhase.Gated(
                    reason = result.reason,
                    canOverride = relaxedGateConfig(result.reason, container.settingsStore.gateConfig()) != null,
                )
                PipelineResult.AlreadyRunning -> Unit
            }
        }
    }

    private fun pollNow(): HomeUiState {
        val response = container.bridge.current()
        val tunnelState = (response as? BridgeResponse.Snapshot)?.snapshot?.state ?: BridgeTunnelState.Unknown
        val consentCallerPackage = (response as? BridgeResponse.ConsentRequired)?.callerPackage
        return HomeUiState(
            tunnelState = tunnelState,
            consentCallerPackage = consentCallerPackage,
            targetDisplayName = container.settingsStore.target()?.displayName,
            showPartialMediaAccessWarning = container.mediaAccess.currentState().showPartialAccessWarning,
        )
    }

    private inner class LiveProgressListener : SyncProgressListener {
        override fun onProgress(event: SyncProgressEvent) {
            runPhase.value = RunPhase.Running(progress = event)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
