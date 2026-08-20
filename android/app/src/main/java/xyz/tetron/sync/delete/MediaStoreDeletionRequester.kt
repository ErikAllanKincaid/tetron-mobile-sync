// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.delete

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import xyz.tetron.sync.media.AndroidMediaAccess

/**
 * SYNC-007's real [DeletionRequester]: resolves each transfer-relative
 * path (relative to [AndroidMediaAccess.cameraRollDirectory], matching
 * what `rsync`'s file list uses) to its MediaStore content `Uri`, then
 * hands the whole verified set to `MediaStore.createDeleteRequest` --
 * gated at API 33 to match this app's other media-related API boundary
 * (spec/sync.py SYNC-007's own "Android 13+" framing), even though the
 * underlying platform call has existed since API 30. [launcher] is the
 * `Activity`-owned seam that actually shows the system confirm dialog
 * (see [DeleteIntentSenderLauncher]).
 *
 * Below API 33, `requestDelete` is a no-op rather than a half-correct
 * implementation: spec/sync.py's "same app-level confirm" for that range
 * needs direct file deletion, which needs `WRITE_EXTERNAL_STORAGE` (this
 * app never requests it) and behaves inconsistently under API 29-32's
 * scoped storage rules in ways that could not be verified without a real
 * low-API device on hand. Manual verification on the API 33+ reference
 * device remains the ENFORCEMENT bar either way (spec/sync.py SYNC-007).
 */
class MediaStoreDeletionRequester(
    private val contentResolver: ContentResolver,
    private val launcher: DeleteIntentSenderLauncher,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : DeletionRequester {
    override fun requestDelete(paths: List<String>) {
        if (apiLevel < Build.VERSION_CODES.TIRAMISU) return
        val uris = paths.mapNotNull(::resolveContentUri)
        if (uris.isEmpty()) return
        launchDeleteRequest(uris)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun launchDeleteRequest(uris: List<Uri>) {
        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
        launcher.launch(pendingIntent.intentSender)
    }

    private fun resolveContentUri(relativePath: String): Uri? {
        val absolutePath = File(AndroidMediaAccess.cameraRollDirectory(), relativePath).path
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DATA} = ?",
            arrayOf(absolutePath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}
