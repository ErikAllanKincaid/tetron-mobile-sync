// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.gates

/**
 * SYNC-004 decision #3: skip + notify, coalesced one notification per
 * [GateReason] per [windowMillis] (~6h provisional default) -- no silent
 * wait, no retry storm. Thread-safe: gate evaluation and any WorkManager/
 * network-change trigger (SYNC-006) can call this from different threads.
 */
class GateNotificationCoalescer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastNotifiedAtMillis = mutableMapOf<GateReason, Long>()

    /**
     * Returns true (and records now) the first time [reason] is seen, or
     * once [windowMillis] has elapsed since the last notification for it;
     * otherwise returns false and does not touch the recorded time.
     */
    @Synchronized
    fun shouldNotify(reason: GateReason): Boolean {
        val now = nowMillis()
        val last = lastNotifiedAtMillis[reason]
        if (last != null && now - last < windowMillis) return false
        lastNotifiedAtMillis[reason] = now
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    }
}
