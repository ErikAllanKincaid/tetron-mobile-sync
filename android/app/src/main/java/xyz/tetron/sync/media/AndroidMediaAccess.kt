// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import xyz.tetron.sync.pipeline.SourcePathProvider
import xyz.tetron.sync.pipeline.SourceSpec
import xyz.tetron.sync.scope.BacklogEstimate
import xyz.tetron.sync.scope.BackupScope
import xyz.tetron.sync.scope.MediaEntry
import xyz.tetron.sync.scope.estimateBacklog
import xyz.tetron.sync.scope.selectInScope

/**
 * SYNC-008: the production [SourcePathProvider] -- reads real runtime
 * permission grants plus the canonical DCIM/Camera directory. No
 * classification logic of its own (that is [resolveMediaAccessState]), so
 * no unit test (same bar as
 * [xyz.tetron.sync.pipeline.AndroidDeviceStateProvider]); real behavior
 * (the permission prompt, the partial-access banner) is verified on-device
 * (SYNC-011). Requesting the runtime permission itself needs an `Activity`
 * (`ActivityResultContracts.RequestMultiplePermissions`), which this class
 * does not have -- same seam split as
 * [xyz.tetron.sync.delete.DeletionRequester]; SYNC-009 owns the request
 * flow (first-run setup / Backup-press time, spec/sync.py SYNC-008), this
 * class only reads whatever grant already exists.
 *
 * SYNC-011 root cause: on API 29+, Scoped Storage's FUSE layer filters raw
 * directory *enumeration* (`readdir`) per-app-UID -- a real device's own
 * recursive walk of `DCIM/Camera` sees only 1 entry even with
 * `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` granted. Opening a *known* path
 * directly is not filtered the same way (confirmed on-device). So on API
 * 29+, [resolve] enumerates the real file list via `MediaStore` (which is
 * never subject to the enumeration filter -- that is its entire purpose)
 * and stages it as a local `--files-from` list, rather than letting
 * oc-rsync recurse into the directory itself. Pre-29 devices have no Scoped
 * Storage restriction, so they keep the plain recursive-walk behavior
 * unchanged from SYNC-008 v1.
 */
class AndroidMediaAccess(
    private val context: Context,
    /** SYNC-012: the persistent backup scope, read fresh each call
     *  (`AppContainer` wires it to `SettingsStore::backupScope`). Defaults
     *  to "everything" so tests and any un-wired caller keep the SYNC-008
     *  behavior. */
    private val scopeProvider: () -> BackupScope = { BackupScope() },
) : SourcePathProvider {

    fun currentPermissionState(): MediaPermissionState = MediaPermissionState(
        readMediaImagesGranted = granted(Manifest.permission.READ_MEDIA_IMAGES),
        readMediaVideoGranted = granted(Manifest.permission.READ_MEDIA_VIDEO),
        readMediaVisualUserSelectedGranted = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        readExternalStorageGranted = granted(Manifest.permission.READ_EXTERNAL_STORAGE),
    )

    fun currentState(): MediaAccessState = resolveMediaAccessState(
        permissionState = currentPermissionState(),
        apiLevel = Build.VERSION.SDK_INT,
        directory = cameraRollDirectory(),
    )

    /** The [SourcePathProvider] contract SYNC-005's pipeline consumes.
     *  SYNC-012: the staged `--files-from` list is the current
     *  [BackupScope] applied to the `MediaStore` roster
     *  ([selectInScope]); [SourceSpec.skippedOversizeCount] is the
     *  size-cap drop count that falls out of the same pass.
     *
     *  SYNC-010: on API 29+ the rsync source root is the external-storage
     *  root and each `--files-from` entry is `DCIM/Camera/<name>`, so
     *  rsync's implied-dirs rebuild that tree under the push destination
     *  (`<module>/<device-label>/DCIM/Camera/<name>`) and the receiver copy
     *  mirrors the phone's MediaStore layout. The pre-29 recursive-walk
     *  path keeps the DCIM/Camera root with no list; its files land flat
     *  under `<device-label>/` (documented asymmetry -- no reference device
     *  is pre-29, matches SYNC-012 decision A2). */
    override fun resolve(): SourceSpec? {
        val cameraDir = currentState().sourcePath ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SourceSpec(rootPath = cameraDir)
        val selection = selectInScope(queryCameraRollEntries(File(cameraDir)), scopeProvider())
        return SourceSpec(
            rootPath = externalStorageRoot(),
            filesFromPath = writeFilesFromList(
                selection.includedNames.map { CAMERA_RELATIVE_PATH + it },
            ),
            skippedOversizeCount = selection.oversizeSkippedCount,
        )
    }

    /**
     * SYNC-012: the local backlog estimate behind the Settings estimate
     * line and the Preview bottom sheet -- the same `MediaStore` roster,
     * aggregated ([estimateBacklog]) instead of staged. No tunnel, no
     * target. API 29+ only (the scope does not apply to the pre-29
     * recursive-walk path -- decision A2); [BacklogEstimate.EMPTY] below
     * that. `Cursor` glue only, so no unit test here -- the aggregation it
     * feeds is tested directly.
     */
    fun backlogEstimate(scope: BackupScope = scopeProvider()): BacklogEstimate {
        val root = currentState().sourcePath ?: return BacklogEstimate.EMPTY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BacklogEstimate.EMPTY
        return estimateBacklog(queryCameraRollEntries(File(root)), scope)
    }

    /**
     * Every image/video `MediaStore` row under `DCIM/Camera/`, reduced to
     * [MediaEntry]. Stale rows are dropped here: `MediaStore`'s index can
     * outlive the file on a lived-in gallery (root-caused SYNC-011), and
     * feeding a dead name into `--files-from` makes oc-rsync `link_stat` it,
     * fail, and set exit 23 -- a misleading "Interrupted" run. [File.exists]
     * is a stat on a *known* path, not a directory listing, so it is not
     * subject to the Scoped Storage enumeration filter. The same stat
     * yields [File.length] for the SYNC-012 size cap -- `max(MediaStore.SIZE,
     * length)` since `MediaStore.SIZE` can be stale (0, or a pre-edit
     * value) and the cap should err toward holding a big file back.
     */
    private fun queryCameraRollEntries(rootDir: File): List<MediaEntry> {
        val entries = ArrayList<MediaEntry>()
        queryCameraRoll().use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val onDisk = File(rootDir, name)
                if (!onDisk.exists()) continue
                val mime = if (cursor.isNull(mimeCol)) null else cursor.getString(mimeCol)
                val size = maxOf(cursor.getLong(sizeCol), onDisk.length())
                entries.add(MediaEntry(displayName = name, mimeType = mime, sizeBytes = size))
            }
        }
        return entries
    }

    /**
     * Writes [names] as a newline-delimited `--files-from` list in
     * app-private storage (overwritten each run -- a transient staging
     * file, not history). Real camera-roll filenames never contain
     * newlines, so no `--from0`/NUL-delimiting is needed. An empty list
     * still writes -- "nothing in scope to back up" is a normal outcome,
     * not a [SourceSpec] resolution failure.
     */
    private fun writeFilesFromList(names: List<String>): String {
        val listFile = File(context.filesDir, FILES_FROM_LIST_NAME)
        listFile.bufferedWriter().use { writer ->
            for (name in names) {
                writer.write(name)
                writer.write("\n")
            }
        }
        return listFile.path
    }

    private fun queryCameraRoll(): Cursor {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            CAMERA_RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        return context.contentResolver.query(collection, projection, selection, selectionArgs, null)
            ?: throw IllegalStateException("MediaStore query returned null cursor")
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Staging file name for [writeFilesFromList], under `context.filesDir`. */
        private const val FILES_FROM_LIST_NAME = "backup_files_from.txt"

        /**
         * `MediaStore.Files.FileColumns.RELATIVE_PATH` value for
         * `DCIM/Camera` -- MediaStore always stores this with a trailing
         * slash.
         */
        private const val CAMERA_RELATIVE_PATH = "DCIM/Camera/"

        /**
         * The real filesystem DCIM/Camera path (plan §Folders: v1 sources
         * are the real path, not a picker). `DIRECTORY_DCIM` is the stable
         * part of the public API for this; MediaStore has no direct
         * folder-resolution call for a specific subfolder.
         */
        fun cameraRollDirectory(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")

        /**
         * SYNC-010: the rsync source root for API 29+ transfers. The
         * `--files-from` entries are relative to this (`DCIM/Camera/<name>`,
         * later other public dirs), so rsync recreates that tree under the
         * push destination. Derived from [cameraRollDirectory] (Camera ->
         * DCIM -> storage root) rather than the separately-deprecated
         * `Environment.getExternalStorageDirectory()`, to keep one path
         * source of truth.
         */
        fun externalStorageRoot(): String =
            cameraRollDirectory().parentFile!!.parentFile!!.path

        /**
         * SYNC-009: the permission set to hand
         * `ActivityResultContracts.RequestMultiplePermissions` -- always
         * asks for *full* access (never
         * `READ_MEDIA_VISUAL_USER_SELECTED` on its own), since v1 must
         * request full access explicitly (spec/sync.py SYNC-008); a
         * partial grant is something the OS can still offer the user from
         * this same system dialog on API 34+, this app just never asks
         * for it directly. Matches [resolveMediaAccessGrant]'s own
         * apiLevel gating.
         */
        fun requiredPermissions(apiLevel: Int): Array<String> = if (apiLevel >= 33) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
