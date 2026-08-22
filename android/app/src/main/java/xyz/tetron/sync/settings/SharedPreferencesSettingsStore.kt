// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.settings

import android.content.Context
import android.content.SharedPreferences
import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.pipeline.SyncTarget

/**
 * SYNC-009: the production [SettingsStore] -- a single `SharedPreferences`
 * file, one key per field. No logic of its own beyond reading/writing with
 * [GateConfig]/[DeleteAfterBackupConfig]'s own defaults as fallbacks, so no
 * unit test (same bar as [xyz.tetron.sync.pipeline.AndroidDeviceStateProvider]);
 * real round-tripping is exercised by every Settings-screen toggle
 * on-device (SYNC-009 ACCEPTANCE).
 */
class SharedPreferencesSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun gateConfig(): GateConfig {
        val defaults = GateConfig()
        return GateConfig(
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, defaults.wifiOnly),
            directOnly = prefs.getBoolean(KEY_DIRECT_ONLY, defaults.directOnly),
            chargingRequired = prefs.getBoolean(KEY_CHARGING_REQUIRED, defaults.chargingRequired),
            lowBatteryPauseEnabled =
                prefs.getBoolean(KEY_LOW_BATTERY_PAUSE_ENABLED, defaults.lowBatteryPauseEnabled),
            lowBatteryThresholdPercent =
                prefs.getInt(KEY_LOW_BATTERY_THRESHOLD, defaults.lowBatteryThresholdPercent),
        )
    }

    override fun setGateConfig(config: GateConfig) {
        prefs.edit()
            .putBoolean(KEY_WIFI_ONLY, config.wifiOnly)
            .putBoolean(KEY_DIRECT_ONLY, config.directOnly)
            .putBoolean(KEY_CHARGING_REQUIRED, config.chargingRequired)
            .putBoolean(KEY_LOW_BATTERY_PAUSE_ENABLED, config.lowBatteryPauseEnabled)
            .putInt(KEY_LOW_BATTERY_THRESHOLD, config.lowBatteryThresholdPercent)
            .apply()
    }

    override fun target(): SyncTarget? {
        val meshIp = prefs.getString(KEY_TARGET_MESH_IP, null) ?: return null
        val module = prefs.getString(KEY_TARGET_MODULE, null) ?: return null
        val port = prefs.getInt(KEY_TARGET_PORT, DEFAULT_TARGET_PORT)
        return SyncTarget(meshIp = meshIp, module = module, port = port)
    }

    override fun setTarget(target: SyncTarget?) {
        val editor = prefs.edit()
        if (target == null) {
            editor.remove(KEY_TARGET_MESH_IP)
                .remove(KEY_TARGET_MODULE)
                .remove(KEY_TARGET_PORT)
        } else {
            editor.putString(KEY_TARGET_MESH_IP, target.meshIp)
                .putString(KEY_TARGET_MODULE, target.module)
                .putInt(KEY_TARGET_PORT, target.port)
        }
        editor.apply()
    }

    override fun deleteAfterBackupConfig(): DeleteAfterBackupConfig =
        DeleteAfterBackupConfig(enabled = prefs.getBoolean(KEY_DELETE_ENABLED, DeleteAfterBackupConfig().enabled))

    override fun setDeleteAfterBackupConfig(config: DeleteAfterBackupConfig) {
        prefs.edit().putBoolean(KEY_DELETE_ENABLED, config.enabled).apply()
    }

    override fun workCadenceHours(): Long =
        prefs.getLong(KEY_WORK_CADENCE_HOURS, DEFAULT_WORK_CADENCE_HOURS)

    override fun setWorkCadenceHours(hours: Long) {
        prefs.edit().putLong(KEY_WORK_CADENCE_HOURS, hours).apply()
    }

    override fun coalesceWindowHours(): Long =
        prefs.getLong(KEY_COALESCE_WINDOW_HOURS, DEFAULT_COALESCE_WINDOW_HOURS)

    override fun setCoalesceWindowHours(hours: Long) {
        prefs.edit().putLong(KEY_COALESCE_WINDOW_HOURS, hours).apply()
    }

    companion object {
        const val PREFS_NAME = "xyz.tetron.sync.settings"

        /** Matches [xyz.tetron.sync.trigger.SyncWorkScheduler.DEFAULT_INTERVAL]
         *  (1 day). */
        const val DEFAULT_WORK_CADENCE_HOURS = 24L

        /** Matches [xyz.tetron.sync.gates.GateNotificationCoalescer
         *  .DEFAULT_WINDOW_MILLIS] (6 hours). */
        const val DEFAULT_COALESCE_WINDOW_HOURS = 6L
        /** Matches [xyz.tetron.sync.pipeline.SyncTarget]'s own default and
         *  tetron-sync-receiver's `DEFAULT_PORT` -- 8873 collided with the
         *  heavily-squatted 8000-9000 dev-tool port range. */
        private const val DEFAULT_TARGET_PORT = 28873

        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_DIRECT_ONLY = "direct_only"
        private const val KEY_CHARGING_REQUIRED = "charging_required"
        private const val KEY_LOW_BATTERY_PAUSE_ENABLED = "low_battery_pause_enabled"
        private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold_percent"
        private const val KEY_TARGET_MESH_IP = "target_mesh_ip"
        private const val KEY_TARGET_MODULE = "target_module"
        private const val KEY_TARGET_PORT = "target_port"
        private const val KEY_DELETE_ENABLED = "delete_after_backup_enabled"
        private const val KEY_WORK_CADENCE_HOURS = "work_cadence_hours"
        private const val KEY_COALESCE_WINDOW_HOURS = "coalesce_window_hours"
    }
}
