// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.delete

import android.content.IntentSender

/**
 * Launches the system delete-confirm `IntentSender` obtained from
 * `MediaStore.createDeleteRequest`. Needs an `Activity`
 * (`ActivityResultContracts.StartIntentSenderForResult`) -- same seam
 * split as SYNC-008's permission-request launchers:
 * [xyz.tetron.sync.MainActivity] owns the real implementation,
 * [MediaStoreDeletionRequester] only calls this contract.
 */
fun interface DeleteIntentSenderLauncher {
    fun launch(intentSender: IntentSender)
}
