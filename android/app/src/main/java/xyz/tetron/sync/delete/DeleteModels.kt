// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.delete

import uniffi.tetron_mobile_sync.SyncProgressEvent
import uniffi.tetron_mobile_sync.SyncProgressListener

/**
 * SYNC-007 (decision #8, USER: "has to be explicit choice"): delete-after-
 * backup, default OFF. [enabled] is the entire opt-in gate -- when false,
 * [xyz.tetron.sync.pipeline.SyncPipeline] never calls [DeletionRequester],
 * regardless of what a run transferred.
 */
data class DeleteAfterBackupConfig(
    val enabled: Boolean = false,
)

/**
 * Wraps the [SyncProgressListener] a [xyz.tetron.sync.pipeline.SyncPipeline]
 * caller supplies (if any) and additionally collects the transfer-relative
 * paths of events that represent a byte-verified regular-file data transfer
 * completing -- `isTransfer && isFinal`, per `SyncProgressEvent`'s doc
 * (src/lib.rs): a skipped/up-to-date file, a mid-transfer tick, or a
 * directory/symlink/hardlink action must never end up in the delete set
 * (spec/sync.py SYNC-007: "never files skipped as already-present -- the
 * mtime+size skip is not a byte check"). Order-preserving; a path is only
 * ever recorded once since the engine emits exactly one final tick per
 * transferred entry.
 */
class TransferredFileCollector(
    private val downstream: SyncProgressListener? = null,
) : SyncProgressListener {
    private val paths = linkedSetOf<String>()

    override fun onProgress(event: SyncProgressEvent) {
        if (event.isTransfer && event.isFinal) {
            event.path?.let { paths.add(it) }
        }
        downstream?.onProgress(event)
    }

    fun transferredPaths(): List<String> = paths.toList()
}

/**
 * Computes the delete-after-backup set: exactly [transferredPaths] when
 * [config] is opted in, otherwise empty -- pure so it is testable without a
 * real transfer or Android permission surface.
 */
fun resolveDeleteSet(config: DeleteAfterBackupConfig, transferredPaths: List<String>): List<String> =
    if (config.enabled) transferredPaths else emptyList()

/**
 * Requests deletion of the given transfer-relative paths (already filtered
 * to the transferred-this-run set by [resolveDeleteSet]). Android 13+
 * (API 33) is `MediaStore.createDeleteRequest`, which shows the system
 * confirm dialog; below API 33 the same app-level confirm is required
 * (spec/sync.py SYNC-007) -- both need an `Activity` to launch the
 * resulting `IntentSender`/dialog, which this pure-Kotlin layer does not
 * have, so (same seam pattern as [xyz.tetron.sync.pipeline.TargetProvider])
 * the real implementation is SYNC-009-owned; this is the contract
 * [xyz.tetron.sync.pipeline.SyncPipeline] consumes. Never called when
 * [resolveDeleteSet] produces an empty list.
 */
fun interface DeletionRequester {
    fun requestDelete(paths: List<String>)
}
