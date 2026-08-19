// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.MeshBridge
import xyz.tetron.sync.bridge.ProviderStatusCaller

/**
 * SYNC-003: the cross-process half of the acceptance gate. Talks to the
 * real installed tetron-mobile's bridge (content://xyz.tetron.mobile.status)
 * from this app's process, which is exactly the shape the runtime takes:
 * our app's classloader, the provider's parcel, our wire mirrors in between.
 *
 * [crossProcessBridgeAnswers] is the automated part and is skipped when
 * tetron-mobile is not installed. The grant-then-snapshot sequence needs a
 * human (MOBILE-024's uniform user grant lives in the main app's
 * notification-launched GrantActivity): tap the consent notification,
 * Allow, then run [grantedBridgeServesSnapshot] manually (it is @Ignore'd
 * for the CI gate, same bar as MOBILE-024's own verification).
 */
@RunWith(AndroidJUnit4::class)
class MeshBridgeDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cachedBridge: MeshBridge =
        MeshBridge(ProviderStatusCaller(context.contentResolver), cacheTtlMillis = 0)

    @Test
    fun crossProcessBridgeAnswers() {
        assumeNotNull("tetron-mobile must be installed for the bridge test", providerInfo())

        val response = cachedBridge.current()

        when (response) {
            is BridgeResponse.Unavailable ->
                fail("installed provider answered Unavailable: cross-process call or mirror parse failed")
            is BridgeResponse.ConsentRequired ->
                assertEquals("consent bundle must name this app", context.packageName, response.callerPackage)
            is BridgeResponse.Snapshot ->
                assertNotNull("granted snapshot must be complete", response.snapshot)
        }
    }

    /**
     * Manual gate: first tap the consent notification posted by
     * tetron-mobile when this app's first query arrives, Allow in
     * GrantActivity, then re-run this test -- it must then pass via the
     * Snapshot branch. Kept out of the automated run because the grant
     * requires a human tap.
     */
    @Ignore("requires manual grant in tetron-mobile's GrantActivity")
    @Test
    fun grantedBridgeServesSnapshot() {
        assumeNotNull("tetron-mobile must be installed for the bridge test", providerInfo())

        val response = cachedBridge.current()
        val snapshot = (response as? BridgeResponse.Snapshot)?.snapshot
        assertNotNull("expected Snapshot after manual grant, got $response", snapshot)
    }

    private fun providerInfo(): ProviderInfo? =
        context.packageManager.resolveContentProvider(ProviderStatusCaller.AUTHORITY, 0)
}