// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SYNC-006 ACCEPTANCE: "an instrumented test ... confirms the WorkManager
 * job is scheduled after first launch". Uses WorkManager's own test
 * harness (a synchronous, in-process executor) rather than waiting on real
 * OS scheduling -- it proves [SyncWorkScheduler.schedule] actually
 * enqueues [SyncWorker] under its unique name, not that the OS runs it on
 * the real ~daily cadence (unverifiable in a test run; SYNC-011 covers
 * real-device behavior for the app as a whole).
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkSchedulerDeviceTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun schedule_enqueuesTheJobUnderItsUniqueName() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workManager = WorkManager.getInstance(context)

        SyncWorkScheduler.schedule(workManager)

        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorkScheduler.UNIQUE_WORK_NAME).get()
        assertTrue("expected the periodic job to be enqueued", workInfos.isNotEmpty())
        assertTrue(
            "expected an ENQUEUED (or already-running) periodic job, got ${workInfos.map { it.state }}",
            workInfos.all { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING },
        )
    }
}
