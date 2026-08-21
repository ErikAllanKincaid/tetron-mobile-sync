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
class AndroidMediaAccess(private val context: Context) : SourcePathProvider {

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

    /** The [SourcePathProvider] contract SYNC-005's pipeline consumes. */
    override fun resolve(): SourceSpec? {
        val root = currentState().sourcePath ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SourceSpec(rootPath = root)
        return SourceSpec(rootPath = root, filesFromPath = writeFilesFromList(root))
    }

    /**
     * Queries `MediaStore` for every image/video row under `DCIM/Camera/`
     * and stages their filenames as a newline-delimited `--files-from`
     * list in app-private storage (overwritten each run -- this is a
     * transient staging file, not history). Real camera-roll filenames
     * never contain newlines, so no `--from0`/NUL-delimiting is needed.
     * A query returning zero rows still writes (and returns) an empty
     * list -- "nothing new to back up" is a normal outcome, not a
     * [SourceSpec] resolution failure.
     *
     * Root-caused SYNC-011: `MediaStore`'s index can go stale (a row for a
     * file that no longer exists on disk, deleted through some path that
     * never triggered a rescan) -- ordinary on a real, lived-in gallery.
     * Feeding a dead name into `--files-from` makes oc-rsync `link_stat`
     * it, fail, and set the transfer's exit code to "partial transfer due
     * to error" even though every other file transfers correctly --
     * surfacing as a misleading "Interrupted" run on the Home/History
     * screens. [File.exists] is a single stat on a *known* path, not a
     * directory listing, so (per the SYNC-011 Scoped Storage root cause
     * above) it is not subject to the enumeration filter -- checking each
     * candidate here is safe and keeps stale rows out of the list `oc-rsync`
     * ever sees, rather than asking the engine to cope with them.
     */
    private fun writeFilesFromList(root: String): String {
        val rootDir = File(root)
        val listFile = File(context.filesDir, FILES_FROM_LIST_NAME)
        listFile.bufferedWriter().use { writer ->
            queryCameraRollNames().use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    if (File(rootDir, name).exists()) {
                        writer.write(name)
                        writer.write("\n")
                    }
                }
            }
        }
        return listFile.path
    }

    private fun queryCameraRollNames(): Cursor {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME)
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
