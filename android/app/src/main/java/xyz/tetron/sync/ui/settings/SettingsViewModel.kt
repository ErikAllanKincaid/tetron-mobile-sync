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
import kotlinx.coroutines.Job
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.pipeline.SyncTarget
import xyz.tetron.sync.settings.DeviceLabel
import xyz.tetron.sync.scope.BacklogEstimate
import xyz.tetron.sync.scope.BackupScope

data class SettingsUiState(
    val gateConfig: GateConfig = GateConfig(),
    val target: SyncTarget? = null,
    val deleteAfterBackupEnabled: Boolean = false,
    /** `null` == "never" (periodic backup is opt-in, spec/sync.py SYNC-006). */
    val workCadenceHours: Long? = null,
    val ownMeshIp: String? = null,
    val engineInfoLine: String = "",
    /** SYNC-012: the persistent backup scope + the local backlog estimate
     *  ([estimateLoading] while the `MediaStore` aggregate query is in
     *  flight). */
    val backupScope: BackupScope = BackupScope(),
    val estimate: BacklogEstimate = BacklogEstimate.EMPTY,
    val estimateLoading: Boolean = false,
    /** Non-null while the advanced "Receiver module name" override field
     *  holds an invalid value. */
    val moduleNameError: String? = null,
)

/**
 * SYNC-009 Settings screen state: gate toggles/values, the target's port
 * and the advanced module-name override (peer selection lives on
 * [xyz.tetron.sync.ui.home.HomeViewModel]), delete-after-backup opt-in,
 * and the WorkManager cadence. Every setter writes straight through
 * [xyz.tetron.sync.settings.SettingsStore] -- `SharedPreferences.apply()`
 * is non-blocking and main-thread-safe by design, so these do not need a
 * background dispatcher the way [refreshOwnMeshIp]'s cross-process bridge
 * call does.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadFromStore()
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshOwnMeshIp()
                delay(ROSTER_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun loadFromStore() {
        val store = container.settingsStore
        val scope = store.backupScope()
        _uiState.update {
            it.copy(
                gateConfig = store.gateConfig(),
                target = store.target(),
                deleteAfterBackupEnabled = store.deleteAfterBackupConfig().enabled,
                workCadenceHours = store.workCadenceHours(),
                engineInfoLine = AppInfo.describeEngine("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"),
                backupScope = scope,
            )
        }
        refreshEstimate(scope)
    }

    /** [SettingsUiState.ownMeshIp] -- plan §IPC bridge's enrollment UX: the
     *  phone's own mesh IP is what goes into the home side's
     *  `rsyncd.conf hosts allow`. (Used to also refresh the roster for the
     *  target editor's peer picker -- that moved to Home along with the
     *  rest of the editor; this poll now exists for ownMeshIp alone.) */
    private suspend fun refreshOwnMeshIp() {
        val response = container.bridge.current()
        val snapshot = (response as? BridgeResponse.Snapshot)?.snapshot
        _uiState.update { it.copy(ownMeshIp = snapshot?.ownMeshIp) }
    }

    fun setGateConfig(config: GateConfig) {
        container.settingsStore.setGateConfig(config)
        _uiState.update { it.copy(gateConfig = config) }
    }

    /** The rest of the target (peer/module) is edited from Home now, via
     *  [xyz.tetron.sync.ui.home.HomeViewModel.setTarget] -- this is only
     *  reachable once a target already exists, since the port field has
     *  nothing to attach to before a peer/module has been picked. */
    fun setTargetPort(port: Int) {
        val current = _uiState.value.target ?: return
        val updated = current.copy(port = port)
        container.settingsStore.setTarget(updated)
        _uiState.update { it.copy(target = updated) }
    }

    /** Advanced only: the rsync module name. It must match the receiver
     *  exactly (same contract as [setTargetPort]) -- there is no discovery.
     *  Empty or the literal default clears the override. Validated with the
     *  same one-safe-path-component rules as the device label. */
    fun setTargetModule(raw: String) {
        val current = _uiState.value.target ?: return
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == SyncTarget.DEFAULT_MODULE) {
            val updated = current.copy(module = SyncTarget.DEFAULT_MODULE)
            container.settingsStore.setTarget(updated)
            _uiState.update { it.copy(target = updated, moduleNameError = null) }
            return
        }
        when (val result = DeviceLabel.validate(trimmed)) {
            is DeviceLabel.Result.Invalid ->
                _uiState.update { it.copy(moduleNameError = result.reason) }
            is DeviceLabel.Result.Valid -> {
                val updated = current.copy(module = result.label)
                container.settingsStore.setTarget(updated)
                _uiState.update { it.copy(target = updated, moduleNameError = null) }
            }
        }
    }

    fun setDeleteAfterBackupEnabled(enabled: Boolean) {
        container.settingsStore.setDeleteAfterBackupConfig(DeleteAfterBackupConfig(enabled = enabled))
        _uiState.update { it.copy(deleteAfterBackupEnabled = enabled) }
    }

    /** `null` == never (cancels the job). Applied to WorkManager
     *  immediately via [AppContainer.applyPeriodicSchedule] (`UPDATE`
     *  policy), not deferred to the next app start. */
    fun setWorkCadenceHours(hours: Long?) {
        container.settingsStore.setWorkCadenceHours(hours)
        _uiState.update { it.copy(workCadenceHours = hours) }
        container.applyPeriodicSchedule()
    }

    // --- SYNC-012: backup scope, backlog estimate ---

    private var estimateJob: Job? = null

    /** Persist [scope] and kick a fresh estimate. Straight-through write
     *  like the gate setters. */
    fun setBackupScope(scope: BackupScope) {
        container.settingsStore.setBackupScope(scope)
        _uiState.update { it.copy(backupScope = scope) }
        refreshEstimate(scope)
    }

    /** Recompute the local backlog estimate off the main thread. The
     *  `MediaStore` aggregate needs no tunnel and no target; a newer call
     *  supersedes an in-flight one. */
    private fun refreshEstimate(scope: BackupScope) {
        estimateJob?.cancel()
        _uiState.update { it.copy(estimateLoading = true) }
        estimateJob = viewModelScope.launch(Dispatchers.IO) {
            val estimate = container.mediaAccess.backlogEstimate(scope)
            _uiState.update { it.copy(estimate = estimate, estimateLoading = false) }
        }
    }

    companion object {
        private const val ROSTER_POLL_INTERVAL_MILLIS = 5_000L
    }
}
