// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import xyz.tetron.sync.pipeline.SourcePathProvider

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
    override fun sourcePath(): String? = currentState().sourcePath

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * The real filesystem DCIM/Camera path (plan §Folders: v1 sources
         * are the real path, not a picker). `DIRECTORY_DCIM` is the stable
         * part of the public API for this; MediaStore has no direct
         * folder-resolution call for a specific subfolder.
         */
        fun cameraRollDirectory(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
    }
}
