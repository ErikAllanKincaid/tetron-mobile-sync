// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import android.content.Context
import android.content.SharedPreferences

/**
 * SYNC-009: the production [RunHistoryStore] -- [InMemoryRunHistoryStore]
 * was always "a usable default until then" (SYNC-005's own doc comment);
 * the History screen needs the last run to survive process death, so this
 * persists the same single-record shape to `SharedPreferences`.
 *
 * The record <-> key/value mapping is [RunRecordCodec], a pure object, so
 * "every [RunRecord] field is actually persisted" is a JVM-unit-tested
 * invariant rather than a hand-maintained list -- SYNC-011 device
 * verification found `cancelled` (TODO #8) and `skippedOversize` (SYNC-012)
 * had both been added to [RunRecord] without this store learning about
 * them, so a cancelled/size-capped run read back after process death as a
 * plain clean run. The `SharedPreferences` glue below stays untested (same
 * bar as [xyz.tetron.sync.settings.SharedPreferencesSettingsStore]); the
 * codec carries the logic.
 */
class SharedPreferencesRunHistoryStore(context: Context) : RunHistoryStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun recordRun(record: RunRecord) {
        val editor = prefs.edit()
        for ((key, value) in RunRecordCodec.encode(record)) {
            when (value) {
                null -> editor.remove(key)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                else -> error("unsupported RunRecord field type for $key: ${value::class}")
            }
        }
        editor.apply()
    }

    override fun lastRun(): RunRecord? {
        if (!prefs.contains(RunRecordCodec.KEY_TIMESTAMP_MILLIS)) return null
        @Suppress("UNCHECKED_CAST")
        return RunRecordCodec.decode(prefs.all as Map<String, Any?>)
    }

    companion object {
        const val PREFS_NAME = "xyz.tetron.sync.history"
    }
}

/**
 * Pure record <-> key/value codec for [SharedPreferencesRunHistoryStore].
 * [encode] must emit a key for every [RunRecord] field (a `null` value
 * means "remove this key"); [decode] must read every one back with the
 * same defaults [RunRecord]'s own constructor uses. `RunRecordCodecTest`
 * round-trips a fully-populated record to keep the two in lockstep.
 */
object RunRecordCodec {
    const val KEY_TIMESTAMP_MILLIS = "timestamp_millis"
    private const val KEY_ADDED = "added"
    private const val KEY_SKIPPED = "skipped"
    private const val KEY_SKIPPED_OVERSIZE = "skipped_oversize"
    private const val KEY_FAILED = "failed"
    private const val KEY_INTERRUPTED = "interrupted"
    private const val KEY_CANCELLED = "cancelled"
    private const val KEY_FAILURE_REASON = "failure_reason"

    fun encode(record: RunRecord): Map<String, Any?> = mapOf(
        KEY_TIMESTAMP_MILLIS to record.timestampMillis,
        KEY_ADDED to record.added,
        KEY_SKIPPED to record.skipped,
        KEY_SKIPPED_OVERSIZE to record.skippedOversize,
        KEY_FAILED to record.failed,
        KEY_INTERRUPTED to record.interrupted,
        KEY_CANCELLED to record.cancelled,
        KEY_FAILURE_REASON to record.failureReason,
    )

    fun decode(map: Map<String, Any?>): RunRecord? {
        val timestamp = map[KEY_TIMESTAMP_MILLIS] as? Long ?: return null
        return RunRecord(
            timestampMillis = timestamp,
            added = map[KEY_ADDED] as? Int ?: 0,
            skipped = map[KEY_SKIPPED] as? Int ?: 0,
            failed = map[KEY_FAILED] as? Int ?: 0,
            interrupted = map[KEY_INTERRUPTED] as? Boolean ?: false,
            failureReason = map[KEY_FAILURE_REASON] as? String,
            cancelled = map[KEY_CANCELLED] as? Boolean ?: false,
            skippedOversize = map[KEY_SKIPPED_OVERSIZE] as? Int ?: 0,
        )
    }
}
