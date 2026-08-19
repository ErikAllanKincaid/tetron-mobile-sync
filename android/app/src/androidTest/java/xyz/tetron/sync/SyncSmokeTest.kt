// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.tetron_mobile_sync.SyncEngine

/**
 * SYNC-001: trivial instrumented smoke test proving the scaffold on a real
 * device/emulator: the FFI chain (JNA load of libtetron_mobile_sync.so,
 * constructor, method call) works in an installed app, and the launcher
 * activity opens. SYNC-003/005/011 grow this harness into the real
 * contract/integration tests.
 */
@RunWith(AndroidJUnit4::class)
class SyncSmokeTest {
    @Test
    fun engine_version_is_nonempty_across_ffi() {
        val version = SyncEngine().version()
        assertFalse(version.isBlank())
        assertEquals("0.1.0", version)
    }

    @Test
    fun main_activity_launches() {
        ActivityScenario.launch(MainActivity::class.java).use { }
    }
}