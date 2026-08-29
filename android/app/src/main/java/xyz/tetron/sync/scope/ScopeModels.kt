// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.scope

/**
 * SYNC-012: the persistent backup scope -- the one saved configuration
 * every trigger path (manual, WorkManager, network-change) applies when it
 * builds the `--files-from` list, through the single `AndroidMediaAccess`
 * chokepoint. There is deliberately no per-run filter: a filter you can
 * forget to apply fails open (a run scoped to just `.jpg` leaves everything
 * else absent at the destination, and the next wider run then uploads it
 * all at once). Files "come back" only when the user widens this scope,
 * which correctly means "back these up from now on too".
 *
 * The five include flags all default ON, so the out-of-the-box behavior is
 * "everything in DCIM/Camera". [includeOtherFiles] is the catch-all: it is
 * the only flag that stops an *unrecognised* type from uploading, so a new
 * capture format (`.webp`, a motion-photo container, a burst format) is
 * never dropped silently -- it stays covered until the user makes a
 * deliberate choice to turn the catch-all off.
 *
 * [bwlimitKib] is not a file-selection field -- [ScopeFilter] ignores it --
 * but it rides in this class because it is persisted in the same place and
 * the [Preset]s span it (Lean sets a ceiling). The pipeline copies it into
 * `SyncRunOptions.bwlimit_kib` (SYNC-002's existing FFI field).
 *
 * `null` for [maxSizeBytes] / [bwlimitKib] means "no cap" / "no limit"
 * (both default OFF, decision B3/B4).
 */
data class BackupScope(
    val includeJpeg: Boolean = true,
    val includeHeic: Boolean = true,
    val includeRaw: Boolean = true,
    val includeVideos: Boolean = true,
    val includeOtherFiles: Boolean = true,
    val maxSizeBytes: Long? = null,
    val bwlimitKib: Long? = null,
)

/**
 * How [ScopeFilter] classifies a single camera-roll entry. Not the same as
 * Android's `MEDIA_TYPE` (the OS types `.jpg`, `.heic` and `.dng` all as
 * `image`); these are the app's own user-facing buckets, each with its own
 * toggle in [BackupScope]. [Other] is everything that matches none of the
 * named sets -- governed by [BackupScope.includeOtherFiles].
 */
enum class MediaKind { Jpeg, Heic, Raw, Video, Other }

/**
 * The per-file outcome of applying a [BackupScope]. [ExcludedType] and
 * [ExcludedOversize] are kept distinct so History can report the two
 * skipped buckets separately (decision A5: an oversize skip must be
 * countable apart from the "already on server" skips that dominate the
 * pipeline's `skipped` count).
 */
sealed interface ScopeDecision {
    data object Included : ScopeDecision

    /** The file's [kind] toggle is OFF in the scope. */
    data class ExcludedType(val kind: MediaKind) : ScopeDecision

    /** The file is larger than [BackupScope.maxSizeBytes]. */
    data object ExcludedOversize : ScopeDecision
}

/**
 * The extension sets backing the named [MediaKind]s. v1 ships [DEFAULT]
 * (decision B6); the v1.1 overflow-menu editor mutates a stored copy of
 * this and hands it to [ScopeFilter] -- which is exactly why the sets are a
 * parameter, not inlined into the classifier (decision B1).
 *
 * Extensions are lowercase, no leading dot.
 */
data class MediaTypeSets(
    val jpeg: Set<String>,
    val heic: Set<String>,
    val raw: Set<String>,
    val video: Set<String>,
) {
    companion object {
        val DEFAULT = MediaTypeSets(
            jpeg = setOf("jpg", "jpeg"),
            heic = setOf("heic", "heif"),
            raw = setOf("dng", "raw", "arw", "nef", "cr2", "cr3", "rw2", "orf", "raf", "srw"),
            video = setOf("mp4", "mov", "3gp", "m4v", "mkv", "webm"),
        )
    }
}

/**
 * SYNC-012: one-tap layers over [BackupScope]. Selecting a preset populates
 * the fields; editing any field flips the selection back to [Custom]
 * ([presetOf] recomputes it). Only "which preset is selected" plus the
 * field values are persisted -- there is no independent preset store.
 */
enum class Preset { Everything, PhotosOnly, Lean, Custom }

/**
 * Lean's size cap and bandwidth ceiling. Starting values, tunable -- the
 * spec calls them "~1 GB" and "a single unconditional value"; nothing in
 * the decision register pins an exact number.
 */
const val LEAN_MAX_SIZE_BYTES: Long = 1L * 1024 * 1024 * 1024
const val LEAN_BWLIMIT_KIB: Long = 2048

/**
 * The [BackupScope] a [preset] expands to. [Preset.Custom] is not a
 * template -- it returns [current] unchanged (the caller keeps whatever the
 * user has set).
 */
fun scopeForPreset(preset: Preset, current: BackupScope): BackupScope = when (preset) {
    Preset.Everything -> BackupScope()
    Preset.PhotosOnly -> BackupScope(includeVideos = false)
    Preset.Lean -> BackupScope(
        includeRaw = false,
        maxSizeBytes = LEAN_MAX_SIZE_BYTES,
        bwlimitKib = LEAN_BWLIMIT_KIB,
    )
    Preset.Custom -> current
}

/**
 * Which [Preset] a scope currently represents -- [Preset.Custom] when it
 * matches none of the built-in templates. The `current` argument passed to
 * [scopeForPreset] is irrelevant for the non-Custom cases (they ignore it),
 * so plain data-class equality is the match.
 */
fun presetOf(scope: BackupScope): Preset = when (scope) {
    scopeForPreset(Preset.Everything, scope) -> Preset.Everything
    scopeForPreset(Preset.PhotosOnly, scope) -> Preset.PhotosOnly
    scopeForPreset(Preset.Lean, scope) -> Preset.Lean
    else -> Preset.Custom
}
