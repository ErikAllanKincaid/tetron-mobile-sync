// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import android.content.Context
import android.content.SharedPreferences

/**
 * SYNC-009: the production [RunHistoryStore] -- [InMemoryRunHistoryStore]
 * was always "a usable default until then" (SYNC-005's own doc comment);
 * the History screen needs the last run to survive process death, so this
 * persists the same single-record shape to `SharedPreferences`. No logic
 * of its own beyond field-by-field read/write, so no unit test (same bar
 * as [xyz.tetron.sync.settings.SharedPreferencesSettingsStore]).
 */
class SharedPreferencesRunHistoryStore(context: Context) : RunHistoryStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun recordRun(record: RunRecord) {
        val editor = prefs.edit()
            .putLong(KEY_TIMESTAMP_MILLIS, record.timestampMillis)
            .putInt(KEY_ADDED, record.added)
            .putInt(KEY_SKIPPED, record.skipped)
            .putInt(KEY_FAILED, record.failed)
            .putBoolean(KEY_INTERRUPTED, record.interrupted)
        if (record.failureReason != null) {
            editor.putString(KEY_FAILURE_REASON, record.failureReason)
        } else {
            editor.remove(KEY_FAILURE_REASON)
        }
        editor.apply()
    }

    override fun lastRun(): RunRecord? {
        if (!prefs.contains(KEY_TIMESTAMP_MILLIS)) return null
        return RunRecord(
            timestampMillis = prefs.getLong(KEY_TIMESTAMP_MILLIS, 0L),
            added = prefs.getInt(KEY_ADDED, 0),
            skipped = prefs.getInt(KEY_SKIPPED, 0),
            failed = prefs.getInt(KEY_FAILED, 0),
            interrupted = prefs.getBoolean(KEY_INTERRUPTED, false),
            failureReason = prefs.getString(KEY_FAILURE_REASON, null),
        )
    }

    companion object {
        const val PREFS_NAME = "xyz.tetron.sync.history"
        private const val KEY_TIMESTAMP_MILLIS = "timestamp_millis"
        private const val KEY_ADDED = "added"
        private const val KEY_SKIPPED = "skipped"
        private const val KEY_FAILED = "failed"
        private const val KEY_INTERRUPTED = "interrupted"
        private const val KEY_FAILURE_REASON = "failure_reason"
    }
}
