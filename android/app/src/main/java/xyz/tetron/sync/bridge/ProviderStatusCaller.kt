// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.bridge

import android.content.ContentResolver
import android.net.Uri
import androidx.core.os.BundleCompat
import xyz.tetron.mobile.StatusSnapshot as WireSnapshot

/**
 * SYNC-003: the production [StatusCaller] -- the MOBILE-024 ContentProvider
 * round trip. Answers come from the provider's cache only; this side never
 * triggers a poll (the contract forbids it). All failure modes (provider
 * absent, SecurityException, BadParcelableException on a snapshot this
 * build's wire mirror can not read, malformed bundle) collapse to
 * [BridgeResponse.Unavailable] -- the bridge never throws to UI.
 */
class ProviderStatusCaller(
    private val contentResolver: ContentResolver,
) : StatusCaller {
    override fun call(): BridgeResponse =
        try {
            val bundle =
                contentResolver.call(STATUS_URI, METHOD_GET_STATUS, null, null)
                    ?: return BridgeResponse.Unavailable
            statusResponseFrom(
                consentRequired = bundle.getBoolean(EXTRA_CONSENT_REQUIRED, false),
                callerPackage = bundle.getString(EXTRA_CALLER_PACKAGE),
                wireSnapshot = BundleCompat.getParcelable(bundle, EXTRA_SNAPSHOT, WireSnapshot::class.java),
            )
        } catch (_: Exception) {
            BridgeResponse.Unavailable
        }

    companion object {
        const val AUTHORITY = "xyz.tetron.mobile.status"
        val STATUS_URI: Uri = Uri.parse("content://$AUTHORITY/")
        const val METHOD_GET_STATUS = "get_status"
        const val EXTRA_SNAPSHOT = "snapshot"
        const val EXTRA_CONSENT_REQUIRED = "consent_required"
        const val EXTRA_CALLER_PACKAGE = "caller_package"
    }
}