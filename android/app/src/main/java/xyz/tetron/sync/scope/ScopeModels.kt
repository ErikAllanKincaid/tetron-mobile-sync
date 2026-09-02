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
 * SYNC-013: the out-of-the-box scope for a NEW install is JPEG + HEIC +
 * Video + Other on, Raw **off** (`.dng` files are 25-80 MB each and a
 * deliberate choice). An existing install keeps whatever it had --
 * `SharedPreferencesSettingsStore.backupScope()` only applies the tighter
 * Raw default when the prefs file is empty, so a running backup is never
 * silently narrowed. [includeOtherFiles] is the catch-all: it is the only
 * flag that stops an *unrecognised* type from uploading, so a new capture
 * format (`.webp`, a motion-photo container, a burst format) is never
 * dropped silently -- it stays covered until the user makes a deliberate
 * choice to turn the catch-all off, which is why it defaults on even in the
 * tightened set.
 *
 * [bwlimitKib] is not a file-selection field -- [ScopeFilter] ignores it --
 * but it rides in this class because it is persisted in the same place. The
 * pipeline copies it into `SyncRunOptions.bwlimit_kib` (SYNC-002's existing
 * FFI field).
 *
 * SYNC-013: [includePictures] is a source-*directory* flag, not a file
 * type -- it adds the top level of `Pictures/` (never its subfolders, so
 * `Pictures/Screenshots/` stays out) to the roster. It defaults off and is
 * orthogonal to the type flags: a `Pictures/` file still has to pass the
 * JPEG/HEIC/Raw/Video/Other toggles.
 *
 * `null` for [maxSizeBytes] / [bwlimitKib] means "no cap" / "no limit"
 * (both default OFF, decision B3).
 */
data class BackupScope(
    val includeJpeg: Boolean = true,
    val includeHeic: Boolean = true,
    val includeRaw: Boolean = false,
    val includeVideos: Boolean = true,
    val includeOtherFiles: Boolean = true,
    val includePictures: Boolean = false,
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
 * (decision B6, widened by SYNC-013 with the common JPEG/HEIC variants);
 * the v1.1 overflow-menu editor mutates a stored copy of this and hands it
 * to [ScopeFilter] -- which is exactly why the sets are a parameter, not
 * inlined into the classifier (decision B1).
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
            jpeg = setOf("jpg", "jpeg", "jpe", "jfif"),
            heic = setOf("heic", "heif", "hif"),
            raw = setOf("dng", "raw", "arw", "nef", "cr2", "cr3", "rw2", "orf", "raf", "srw"),
            video = setOf("mp4", "mov", "3gp", "m4v", "mkv", "webm"),
        )
    }
}
