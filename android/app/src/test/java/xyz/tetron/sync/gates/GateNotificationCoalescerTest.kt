// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYNC-004 ACCEPTANCE: coalescing drops a second notification for the same
 * reason inside the window, one window per reason (not global), and
 * re-allows after the window elapses.
 */
class GateNotificationCoalescerTest {

    private class FakeClock {
        var now: Long = 0
        fun current(): Long = now
    }

    @Test
    fun firstNotificationForReason_isAllowed() {
        val clock = FakeClock()
        val coalescer = GateNotificationCoalescer(windowMillis = 6 * 60 * 60 * 1000L, nowMillis = clock::current)

        assertTrue(coalescer.shouldNotify(GateReason.NotOnWifi))
    }

    @Test
    fun secondNotificationForSameReason_withinWindow_isDropped() {
        val clock = FakeClock()
        val coalescer = GateNotificationCoalescer(windowMillis = 6 * 60 * 60 * 1000L, nowMillis = clock::current)

        assertTrue(coalescer.shouldNotify(GateReason.LowBattery))
        clock.now += 60_000
        assertFalse(coalescer.shouldNotify(GateReason.LowBattery))
    }

    @Test
    fun notificationForSameReason_atOrAfterWindow_isAllowedAgain() {
        val clock = FakeClock()
        val windowMillis = 6 * 60 * 60 * 1000L
        val coalescer = GateNotificationCoalescer(windowMillis = windowMillis, nowMillis = clock::current)

        assertTrue(coalescer.shouldNotify(GateReason.TunnelNotActive))
        clock.now += windowMillis
        assertTrue(coalescer.shouldNotify(GateReason.TunnelNotActive))
    }

    @Test
    fun differentReasons_areCoalescedIndependently() {
        val clock = FakeClock()
        val coalescer = GateNotificationCoalescer(windowMillis = 6 * 60 * 60 * 1000L, nowMillis = clock::current)

        assertTrue(coalescer.shouldNotify(GateReason.NotOnWifi))
        assertTrue("a different reason must not be suppressed by NotOnWifi's window", coalescer.shouldNotify(GateReason.RelayOnlyPath))
    }

    @Test
    fun droppedNotification_doesNotResetTheWindow() {
        val clock = FakeClock()
        val windowMillis = 6 * 60 * 60 * 1000L
        val coalescer = GateNotificationCoalescer(windowMillis = windowMillis, nowMillis = clock::current)

        assertTrue(coalescer.shouldNotify(GateReason.ChargingRequired))
        clock.now += windowMillis - 1
        assertFalse(coalescer.shouldNotify(GateReason.ChargingRequired))
        clock.now += 1
        assertTrue(coalescer.shouldNotify(GateReason.ChargingRequired))
    }
}
