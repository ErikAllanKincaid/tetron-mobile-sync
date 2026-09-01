// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.settings

import java.util.UUID

/**
 * SYNC-010: the per-device label is the first path component written under
 * the receiver module (`rsync://<ip>:<port>/<module>/<device-label>/...`),
 * so it must be exactly one safe path segment. This is the pure
 * validation/normalisation used by the Settings field and by
 * [SharedPreferencesSettingsStore] when it fills in a fallback.
 */
object DeviceLabel {

    /** Longest label accepted. Comfortably inside any filesystem's
     *  per-component limit and keeps the receiver path readable. */
    const val MAX_LENGTH = 64

    /** A generated fallback so a fresh install backs up with no setup
     *  (plan §1.2: "a generated stable fallback ... persisted in app
     *  prefs"). Persisted once by the store and reused thereafter. */
    fun generateFallback(): String = "phone-" + UUID.randomUUID().toString().take(8)

    sealed interface Result {
        data class Valid(val label: String) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Trims surrounding whitespace, then accepts the label only if it is a
     * single path component: non-empty, no `/`, not `.` or `..`, no leading
     * `.`, no control characters, within [MAX_LENGTH].
     */
    fun validate(raw: String): Result {
        val label = raw.trim()
        return when {
            label.isEmpty() -> Result.Invalid("Enter a label.")
            label.length > MAX_LENGTH -> Result.Invalid("Use $MAX_LENGTH characters or fewer.")
            label.contains('/') -> Result.Invalid("No slashes -- this is one folder name.")
            label == "." || label == ".." -> Result.Invalid("\"$label\" is not a usable folder name.")
            label.startsWith('.') -> Result.Invalid("Cannot start with a dot.")
            label.any { it.isISOControl() } -> Result.Invalid("No control characters.")
            else -> Result.Valid(label)
        }
    }

    /** Convenience: the normalised label if valid, else `null`. */
    fun normalizedOrNull(raw: String): String? = (validate(raw) as? Result.Valid)?.label
}
