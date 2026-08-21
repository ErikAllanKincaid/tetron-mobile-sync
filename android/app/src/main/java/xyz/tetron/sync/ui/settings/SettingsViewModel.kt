// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.tetron.sync.AppContainer
import xyz.tetron.sync.AppInfo
import xyz.tetron.sync.BuildConfig
import xyz.tetron.sync.bridge.BridgePeer
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.pipeline.SyncTarget

data class SettingsUiState(
    val gateConfig: GateConfig = GateConfig(),
    val target: SyncTarget? = null,
    val rosterPeers: List<BridgePeer> = emptyList(),
    val deleteAfterBackupEnabled: Boolean = false,
    val workCadenceHours: Long = 24L,
    val coalesceWindowHours: Long = 6L,
    val ownMeshIp: String? = null,
    val engineInfoLine: String = "",
)

/**
 * SYNC-009 Settings screen state: gate toggles/values, target config
 * (module + display name text, mesh-IP picked from the live bridge
 * roster), delete-after-backup opt-in, WorkManager cadence, and the
 * notification coalescing window. Every setter writes straight through
 * [xyz.tetron.sync.settings.SettingsStore] -- `SharedPreferences.apply()`
 * is non-blocking and main-thread-safe by design, so these do not need a
 * background dispatcher the way [refreshRoster]'s cross-process bridge
 * call does.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadFromStore()
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshRoster()
                delay(ROSTER_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun loadFromStore() {
        val store = container.settingsStore
        _uiState.update {
            it.copy(
                gateConfig = store.gateConfig(),
                target = store.target(),
                deleteAfterBackupEnabled = store.deleteAfterBackupConfig().enabled,
                workCadenceHours = store.workCadenceHours(),
                coalesceWindowHours = store.coalesceWindowHours(),
                engineInfoLine = AppInfo.describeEngine("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"),
            )
        }
    }

    /** Also refreshes [SettingsUiState.ownMeshIp] -- plan §IPC bridge's
     *  enrollment UX: the phone's own mesh IP is what goes into the home
     *  side's `rsyncd.conf hosts allow`, and the roster/own-IP come from
     *  the exact same bridge snapshot, so one poll covers both. */
    private suspend fun refreshRoster() {
        val response = container.bridge.current()
        val snapshot = (response as? BridgeResponse.Snapshot)?.snapshot
        _uiState.update { it.copy(rosterPeers = snapshot?.peers ?: emptyList(), ownMeshIp = snapshot?.ownMeshIp) }
    }

    fun setGateConfig(config: GateConfig) {
        container.settingsStore.setGateConfig(config)
        _uiState.update { it.copy(gateConfig = config) }
    }

    fun setTarget(target: SyncTarget?) {
        container.settingsStore.setTarget(target)
        _uiState.update { it.copy(target = target) }
    }

    fun setDeleteAfterBackupEnabled(enabled: Boolean) {
        container.settingsStore.setDeleteAfterBackupConfig(DeleteAfterBackupConfig(enabled = enabled))
        _uiState.update { it.copy(deleteAfterBackupEnabled = enabled) }
    }

    /** Takes effect next app start (WorkManager `KEEP`, see
     *  [AppContainer.schedulePeriodicWork]), not this instant. */
    fun setWorkCadenceHours(hours: Long) {
        container.settingsStore.setWorkCadenceHours(hours)
        _uiState.update { it.copy(workCadenceHours = hours) }
    }

    /** Takes effect next app start (the coalescer is constructed once, see
     *  [AppContainer]), not this instant. */
    fun setCoalesceWindowHours(hours: Long) {
        container.settingsStore.setCoalesceWindowHours(hours)
        _uiState.update { it.copy(coalesceWindowHours = hours) }
    }

    companion object {
        private const val ROSTER_POLL_INTERVAL_MILLIS = 5_000L
    }
}
