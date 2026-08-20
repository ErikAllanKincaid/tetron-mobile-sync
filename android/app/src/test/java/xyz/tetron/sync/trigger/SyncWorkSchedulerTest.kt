// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import androidx.work.NetworkType
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

/** SYNC-006: the periodic request is built with the right cadence and a
 *  CONNECTED constraint (a coarse pre-filter; SYNC-004's real gates still
 *  run every firing -- see [SyncWorkScheduler]'s doc). */
class SyncWorkSchedulerTest {

    @Test
    fun buildPeriodicRequest_usesGivenInterval() {
        val request = SyncWorkScheduler.buildPeriodicRequest(Duration.ofHours(6))
        assertEquals(Duration.ofHours(6).toMillis(), request.workSpec.intervalDuration)
    }

    @Test
    fun buildPeriodicRequest_requiresConnectedNetwork() {
        val request = SyncWorkScheduler.buildPeriodicRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }
}
