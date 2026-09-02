// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.settings

import android.content.Context
import android.content.SharedPreferences
import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.pipeline.SyncTarget
import xyz.tetron.sync.scope.BackupScope

/**
 * SYNC-009: the production [SettingsStore] -- a single `SharedPreferences`
 * file, one key per field. No logic of its own beyond reading/writing with
 * [GateConfig]/[DeleteAfterBackupConfig]'s own defaults as fallbacks, so no
 * unit test (same bar as [xyz.tetron.sync.pipeline.AndroidDeviceStateProvider]);
 * real round-tripping is exercised by every Settings-screen toggle
 * on-device (SYNC-009 ACCEPTANCE).
 */
class SharedPreferencesSettingsStore(
    context: Context,
    /** The phone's own mesh hostname (MOBILE-024 `ownHostname`), read once
     *  the first time a device label is needed so a fresh install labels
     *  its receiver folder with the hostname instead of an opaque id.
     *  Snapshot, not a live binding -- a later hostname change does not
     *  move an already-persisted label. */
    private val ownHostname: () -> String? = { null },
) : SettingsStore {
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
        // A missing module key is the normal case now: it means "the
        // default". Only an advanced override is ever stored.
        val module = prefs.getString(KEY_TARGET_MODULE, null) ?: SyncTarget.DEFAULT_MODULE
        val port = prefs.getInt(KEY_TARGET_PORT, DEFAULT_TARGET_PORT)
        return SyncTarget(meshIp = meshIp, module = module, port = port, deviceLabel = deviceLabel())
    }

    override fun setTarget(target: SyncTarget?) {
        val editor = prefs.edit()
        if (target == null) {
            editor.remove(KEY_TARGET_MESH_IP)
                .remove(KEY_TARGET_MODULE)
                .remove(KEY_TARGET_PORT)
            // KEY_TARGET_DEVICE_LABEL is deliberately kept: the label
            // identifies this phone on the receiver and should survive
            // re-pointing at a different target.
        } else {
            editor.putString(KEY_TARGET_MESH_IP, target.meshIp)
                .putInt(KEY_TARGET_PORT, target.port)
                .putString(
                    KEY_TARGET_DEVICE_LABEL,
                    DeviceLabel.normalizedOrNull(target.deviceLabel) ?: deviceLabel(),
                )
            // Store the module only when it is a real override; the default
            // is represented by the key's absence.
            if (target.module == SyncTarget.DEFAULT_MODULE) {
                editor.remove(KEY_TARGET_MODULE)
            } else {
                editor.putString(KEY_TARGET_MODULE, target.module)
            }
        }
        editor.apply()
    }

    /** The persisted device label, generating and persisting a fallback the
     *  first time it is read so a fresh install has a valid label with no
     *  setup (SYNC-010). The fallback is the phone's own mesh hostname when
     *  available, else `phone-<8hex>`. */
    private fun deviceLabel(): String {
        prefs.getString(KEY_TARGET_DEVICE_LABEL, null)?.let { return it }
        val fallback = DeviceLabel.generateFallback(ownHostname())
        prefs.edit().putString(KEY_TARGET_DEVICE_LABEL, fallback).apply()
        return fallback
    }

    override fun deleteAfterBackupConfig(): DeleteAfterBackupConfig =
        DeleteAfterBackupConfig(enabled = prefs.getBoolean(KEY_DELETE_ENABLED, DeleteAfterBackupConfig().enabled))

    override fun setDeleteAfterBackupConfig(config: DeleteAfterBackupConfig) {
        prefs.edit().putBoolean(KEY_DELETE_ENABLED, config.enabled).apply()
    }

    override fun backupScope(): BackupScope {
        val d = BackupScope()
        // SYNC-013: Raw defaults OFF for a new install, but an existing
        // install that never opened the scope UI has no KEY_SCOPE_RAW and
        // must keep the old ON -- never silently narrow a running backup.
        // "Existing" == the prefs file already holds some other key; a
        // genuinely fresh install has an empty file. Once any scope toggle
        // is saved, setBackupScope writes every key and this stops
        // mattering.
        val rawDefault = if (prefs.all.isEmpty()) d.includeRaw else true
        return BackupScope(
            includeJpeg = prefs.getBoolean(KEY_SCOPE_JPEG, d.includeJpeg),
            includeHeic = prefs.getBoolean(KEY_SCOPE_HEIC, d.includeHeic),
            includeRaw = prefs.getBoolean(KEY_SCOPE_RAW, rawDefault),
            includeVideos = prefs.getBoolean(KEY_SCOPE_VIDEOS, d.includeVideos),
            includeOtherFiles = prefs.getBoolean(KEY_SCOPE_OTHER_FILES, d.includeOtherFiles),
            // SYNC-013 folder flag: default off, safe for a new or existing
            // install alike (an existing user just does not get Pictures
            // until they opt in).
            includePictures = prefs.getBoolean(KEY_SCOPE_PICTURES, d.includePictures),
            // A real cap/limit is always > 0; -1 is the "unset" sentinel
            // (SharedPreferences has no nullable Long).
            maxSizeBytes = prefs.getLong(KEY_SCOPE_MAX_SIZE_BYTES, -1L).takeIf { it > 0 },
            bwlimitKib = prefs.getLong(KEY_SCOPE_BWLIMIT_KIB, -1L).takeIf { it > 0 },
        )
    }

    override fun setBackupScope(scope: BackupScope) {
        prefs.edit()
            .putBoolean(KEY_SCOPE_JPEG, scope.includeJpeg)
            .putBoolean(KEY_SCOPE_HEIC, scope.includeHeic)
            .putBoolean(KEY_SCOPE_RAW, scope.includeRaw)
            .putBoolean(KEY_SCOPE_VIDEOS, scope.includeVideos)
            .putBoolean(KEY_SCOPE_OTHER_FILES, scope.includeOtherFiles)
            .putBoolean(KEY_SCOPE_PICTURES, scope.includePictures)
            .putLong(KEY_SCOPE_MAX_SIZE_BYTES, scope.maxSizeBytes ?: -1L)
            .putLong(KEY_SCOPE_BWLIMIT_KIB, scope.bwlimitKib ?: -1L)
            .apply()
    }

    /** `null` (key absent) means "never" -- periodic backup is opt-in. A
     *  previously-set interval (the key is present) is left as the user set
     *  it; the migration never rewrites it. */
    override fun workCadenceHours(): Long? =
        if (prefs.contains(KEY_WORK_CADENCE_HOURS)) prefs.getLong(KEY_WORK_CADENCE_HOURS, 24L) else null

    override fun setWorkCadenceHours(hours: Long?) {
        prefs.edit().apply {
            if (hours == null) remove(KEY_WORK_CADENCE_HOURS) else putLong(KEY_WORK_CADENCE_HOURS, hours)
        }.apply()
    }

    companion object {
        const val PREFS_NAME = "xyz.tetron.sync.settings"

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
        private const val KEY_TARGET_DEVICE_LABEL = "target_device_label"
        private const val KEY_DELETE_ENABLED = "delete_after_backup_enabled"
        private const val KEY_WORK_CADENCE_HOURS = "work_cadence_hours"
        private const val KEY_SCOPE_JPEG = "scope_include_jpeg"
        private const val KEY_SCOPE_HEIC = "scope_include_heic"
        private const val KEY_SCOPE_RAW = "scope_include_raw"
        private const val KEY_SCOPE_VIDEOS = "scope_include_videos"
        private const val KEY_SCOPE_OTHER_FILES = "scope_include_other_files"
        private const val KEY_SCOPE_PICTURES = "scope_include_pictures"
        private const val KEY_SCOPE_MAX_SIZE_BYTES = "scope_max_size_bytes"
        private const val KEY_SCOPE_BWLIMIT_KIB = "scope_bwlimit_kib"
    }
}
