// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.settings

import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.pipeline.SyncTarget
import xyz.tetron.sync.scope.BackupScope

/**
 * SYNC-009: persisted app settings -- gate config, the backup target,
 * delete-after-backup opt-in, and the WorkManager periodic cadence. Every
 * value is read fresh (no caching in this layer) so a Settings-screen
 * toggle is reflected on the very next pipeline run (SYNC-009 ACCEPTANCE:
 * "every settings toggle roundtrips ... reflected in the next run's gate
 * evaluation"); [SyncPipeline]'s own `gateConfig`/`deleteConfig`
 * constructor params are suppliers for exactly this reason.
 */
interface SettingsStore {
    fun gateConfig(): GateConfig
    fun setGateConfig(config: GateConfig)

    /** `null` when no target has been configured yet -- matches
     *  [xyz.tetron.sync.pipeline.TargetProvider]'s own contract. */
    fun target(): SyncTarget?
    fun setTarget(target: SyncTarget?)

    fun deleteAfterBackupConfig(): DeleteAfterBackupConfig
    fun setDeleteAfterBackupConfig(config: DeleteAfterBackupConfig)

    /** SYNC-012: the one persistent backup scope every trigger path applies
     *  when it builds the `--files-from` list. Read fresh per run, same
     *  live-read contract as [gateConfig] -- a Settings toggle takes effect
     *  on the very next run. The selected [xyz.tetron.sync.scope.Preset] is
     *  not stored: it is derived from the scope
     *  ([xyz.tetron.sync.scope.presetOf]). */
    fun backupScope(): BackupScope
    fun setBackupScope(scope: BackupScope)

    /** [xyz.tetron.sync.trigger.SyncWorkScheduler]'s periodic interval, in
     *  hours, or `null` for "never" (spec/sync.py SYNC-006: periodic backup
     *  is opt-in, default off). A user change is applied immediately by
     *  [xyz.tetron.sync.ui.settings.SettingsViewModel], not deferred to the
     *  next app start. */
    fun workCadenceHours(): Long?
    fun setWorkCadenceHours(hours: Long?)
}
