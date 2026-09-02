// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.media

import android.os.Build
import android.provider.MediaStore
import xyz.tetron.sync.scope.BackupScope

/**
 * SYNC-013: one standard MediaStore public directory that can feed a
 * backup. [relativePath] is the exact `RELATIVE_PATH` value (trailing
 * slash -- the query matches it with `=`, never `LIKE`, so subfolders such
 * as `Pictures/Screenshots/` never match); [mediaTypes] are the
 * `MEDIA_TYPE` values to pull; [minApiLevel] is the OS version its
 * `Environment.DIRECTORY_*` constant first existed on; [enabled] is the
 * [BackupScope] predicate that turns it on (`DCIM/Camera` is always on).
 *
 * The pure parts ([activeSourceDirs], [mediaSourceSelection]) live here
 * rather than inside [AndroidMediaAccess] so the "which directories, in
 * what order" and "what SQL" decisions are unit-tested, not left to the
 * on-device pass -- `MediaStore` / `Build` here are compile-time `int` /
 * `String` constants, inlined, so plain JUnit reaches them.
 */
data class MediaSourceDir(
    val relativePath: String,
    val mediaTypes: List<Int>,
    val minApiLevel: Int,
    val enabled: (BackupScope) -> Boolean,
)

/**
 * The fixed, closed set of source directories (SYNC-013). List order is the
 * `--files-from` staging order. `Recordings` (API 31+, audio+video,
 * bypasses the type filters) is a deferred follow-up: it needs an API 31+
 * device to verify, so it is not wired yet.
 */
val SOURCE_DIRS: List<MediaSourceDir> = listOf(
    MediaSourceDir(
        relativePath = "DCIM/Camera/",
        mediaTypes = listOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
        ),
        minApiLevel = Build.VERSION_CODES.BASE,
        enabled = { true },
    ),
    MediaSourceDir(
        relativePath = "Pictures/",
        mediaTypes = listOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
        ),
        minApiLevel = Build.VERSION_CODES.BASE,
        enabled = { it.includePictures },
    ),
)

/** The directories to actually query for [scope] on an OS at [apiLevel]:
 *  in [all] order, keeping the ones whose [MediaSourceDir.minApiLevel] this
 *  OS meets and whose [MediaSourceDir.enabled] predicate the scope
 *  satisfies. */
fun activeSourceDirs(
    scope: BackupScope,
    apiLevel: Int,
    all: List<MediaSourceDir> = SOURCE_DIRS,
): List<MediaSourceDir> =
    all.filter { it.minApiLevel <= apiLevel && it.enabled(scope) }

/** The `(selection, selectionArgs)` for one directory: an EXACT
 *  `RELATIVE_PATH` match (no `LIKE`, so `Pictures/Screenshots/` is out) and
 *  a `MEDIA_TYPE IN (...)` list sized to [MediaSourceDir.mediaTypes]. */
fun mediaSourceSelection(dir: MediaSourceDir): Pair<String, Array<String>> {
    val placeholders = dir.mediaTypes.joinToString(", ") { "?" }
    val selection =
        "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($placeholders)"
    val args = (listOf(dir.relativePath) + dir.mediaTypes.map { it.toString() }).toTypedArray()
    return selection to args
}
