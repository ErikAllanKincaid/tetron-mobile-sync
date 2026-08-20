// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.media

import java.io.File

/**
 * SYNC-008: the three-way grant state v1 cares about. `Partial` only exists
 * from Android 14 (API 34)'s `READ_MEDIA_VISUAL_USER_SELECTED` ("select
 * photos") grant; earlier OS versions only ever produce `Full`/`NotGranted`.
 */
enum class MediaAccessGrant {
    NotGranted,
    Partial,
    Full,
}

/**
 * Raw permission reads a caller supplies. Real callers fill this from
 * `ContextCompat.checkSelfPermission` (see [AndroidMediaAccess]); tests fake
 * it directly so [resolveMediaAccessGrant] is exercised across the whole
 * state matrix with no Android dependency (spec/sync.py SYNC-008
 * ACCEPTANCE: "partial-access grant detection from a mocked permission
 * state").
 */
data class MediaPermissionState(
    val readMediaImagesGranted: Boolean,
    val readMediaVideoGranted: Boolean,
    val readMediaVisualUserSelectedGranted: Boolean,
    val readExternalStorageGranted: Boolean,
)

/**
 * Classifies [state] into a [MediaAccessGrant]. [apiLevel] (`Build.VERSION
 * .SDK_INT` in production) gates which permissions are even meaningful:
 * - API 33 ("Tiramisu") introduced `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`
 *   as the full-access grant, replacing `READ_EXTERNAL_STORAGE` for media.
 * - API 34 ("Upside Down Cake") added `READ_MEDIA_VISUAL_USER_SELECTED`,
 *   the partial "select photos" grant this requirement must warn about; it
 *   does not exist pre-34, so it is never consulted below that level even
 *   if a caller's [MediaPermissionState] happens to set it.
 * - Below API 33, `READ_EXTERNAL_STORAGE` is the only relevant grant.
 *
 * A device offers full access as one OS-driven choice (both media
 * permissions together), so "images granted, video not" is not a real
 * combination -- this only checks both together rather than modelling a
 * fourth partial-by-media-type state.
 */
fun resolveMediaAccessGrant(state: MediaPermissionState, apiLevel: Int): MediaAccessGrant = when {
    apiLevel >= 33 -> when {
        state.readMediaImagesGranted && state.readMediaVideoGranted -> MediaAccessGrant.Full
        apiLevel >= 34 && state.readMediaVisualUserSelectedGranted -> MediaAccessGrant.Partial
        else -> MediaAccessGrant.NotGranted
    }
    else -> if (state.readExternalStorageGranted) MediaAccessGrant.Full else MediaAccessGrant.NotGranted
}

/**
 * Resolves the DCIM/Camera source path for SYNC-005's
 * [xyz.tetron.sync.pipeline.SourcePathProvider] contract. `null` when
 * [grant] is [MediaAccessGrant.NotGranted] (reads are not legal) or when
 * [directory] does not exist -- both surface through the pipeline as
 * `GateReason.TargetUnreachable`, never a crash (spec/sync.py SYNC-008).
 * [MediaAccessGrant.Partial] still resolves a path: a partial grant backs
 * up whatever subset of the roll the user selected, it does not block the
 * run outright (spec/sync.py SYNC-008: "only selected photos will back
 * up", not "backup is unavailable"). [directory] is injected so tests never
 * touch the real filesystem.
 */
fun resolveSourcePath(grant: MediaAccessGrant, directory: File): String? {
    if (grant == MediaAccessGrant.NotGranted) return null
    return if (directory.isDirectory) directory.path else null
}

/**
 * Aggregate state SYNC-009's home/settings screens observe: the grant, the
 * resolved source path (or lack of one), and whether the partial-access
 * warning banner belongs on screen.
 */
data class MediaAccessState(
    val grant: MediaAccessGrant,
    val sourcePath: String?,
) {
    val showPartialAccessWarning: Boolean get() = grant == MediaAccessGrant.Partial
}

/** Combines [resolveMediaAccessGrant] and [resolveSourcePath] into one call. */
fun resolveMediaAccessState(
    permissionState: MediaPermissionState,
    apiLevel: Int,
    directory: File,
): MediaAccessState {
    val grant = resolveMediaAccessGrant(permissionState, apiLevel)
    return MediaAccessState(grant = grant, sourcePath = resolveSourcePath(grant, directory))
}
