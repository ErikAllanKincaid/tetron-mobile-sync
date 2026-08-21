// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xyz.tetron.sync.gates.GateReason
import xyz.tetron.sync.pipeline.RunRecord
import xyz.tetron.sync.ui.describeGateReason

/**
 * SYNC-009 notifications: channels + copy for gate reasons (coalesced by
 * [xyz.tetron.sync.gates.GateNotificationCoalescer] before
 * [notifyGated] is ever called -- this class posts unconditionally,
 * SYNC-004's decision #3 "skip and notify at most once per window" is
 * already enforced upstream) and completion/failure (never coalesced --
 * every attempted run gets one, wired through [xyz.tetron.sync.pipeline
 * .SyncPipeline]'s `onRunCompleted`). Exact copy is this requirement's own
 * open item (spec/sync.py SYNC-009); wording here is a reasonable v1, not
 * a finished design pass.
 *
 * minSdk 26 means a [NotificationChannel] always exists to create (no
 * `Build.VERSION.SDK_INT` gate needed, unlike most notification code that
 * also targets pre-8.0). `POST_NOTIFICATIONS` (API 33+) may still be
 * denied -- [NotificationManagerCompat.notify] throws `SecurityException`
 * in that case rather than silently no-op-ing, so [postSafely] must catch
 * it: this fires from the pipeline's own background thread ([SyncWorker],
 * the network-change executor, or a manual run), and a notification
 * permission gap must never take the transfer down with it.
 */
class SyncNotifier(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GATED, "Backup paused", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Backup results", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun notifyGated(reason: GateReason) {
        postSafely(
            NOTIFICATION_ID_GATED,
            buildNotification(CHANNEL_GATED, "Backup paused", describeGateReason(reason)),
        )
    }

    fun notifyRunCompleted(record: RunRecord) {
        val (title, text) = when {
            record.failed > 0 -> "Backup failed" to (record.failureReason ?: "Unknown error")
            record.interrupted -> "Backup interrupted" to "${record.added} added so far -- will resume next run"
            else -> "Backup complete" to "${record.added} added, ${record.skipped} skipped"
        }
        postSafely(NOTIFICATION_ID_RESULT, buildNotification(CHANNEL_RESULT, title, text))
    }

    private fun buildNotification(channelId: String, title: String, text: String) =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    private fun postSafely(id: Int, notification: android.app.Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted -- drop silently, never crash
            // the pipeline thread this was called from.
        }
    }

    companion object {
        private const val CHANNEL_GATED = "gated"
        private const val CHANNEL_RESULT = "result"
        private const val NOTIFICATION_ID_GATED = 1
        private const val NOTIFICATION_ID_RESULT = 2
    }
}
