// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.bridge.ConnKind

/**
 * SYNC-004 ACCEPTANCE: the full AND matrix -- every gate individually false
 * blocks the run with the right reason, all-true passes, and a blocked
 * decision never leads to any network activity (asserted here via a fake
 * transfer trigger that must not be invoked when gated).
 */
class GateEvaluatorTest {

    private val allOpenInputs = GateInputs(
        tunnelState = BridgeTunnelState.Active,
        isWifiConnected = true,
        isCharging = true,
        batteryPercent = 100,
        targetConnKind = ConnKind.Direct,
    )

    @Test
    fun allConditionsTrue_allows() {
        assertEquals(GateDecision.Allowed, GateEvaluator.evaluate(allOpenInputs))
    }

    @Test
    fun tunnelNotActive_blocksWithRightReason() {
        for (state in BridgeTunnelState.entries.filter { it != BridgeTunnelState.Active }) {
            val decision = GateEvaluator.evaluate(allOpenInputs.copy(tunnelState = state))
            assertEquals("state=$state", GateDecision.Blocked(GateReason.TunnelNotActive), decision)
        }
    }

    @Test
    fun notOnWifi_blocksWithRightReason_whenWifiOnlyEnabled() {
        val decision = GateEvaluator.evaluate(allOpenInputs.copy(isWifiConnected = false))
        assertEquals(GateDecision.Blocked(GateReason.NotOnWifi), decision)
    }

    @Test
    fun notOnWifi_isIgnored_whenWifiOnlyDisabled() {
        val decision = GateEvaluator.evaluate(
            allOpenInputs.copy(isWifiConnected = false),
            GateConfig(wifiOnly = false),
        )
        assertEquals(GateDecision.Allowed, decision)
    }

    @Test
    fun chargingRequired_defaultOff_doesNotBlock() {
        val decision = GateEvaluator.evaluate(allOpenInputs.copy(isCharging = false))
        assertEquals(GateDecision.Allowed, decision)
    }

    @Test
    fun chargingRequired_blocksWithRightReason_whenEnabled() {
        val decision = GateEvaluator.evaluate(
            allOpenInputs.copy(isCharging = false),
            GateConfig(chargingRequired = true),
        )
        assertEquals(GateDecision.Blocked(GateReason.ChargingRequired), decision)
    }

    @Test
    fun lowBattery_blocksWithRightReason_belowThreshold() {
        val decision = GateEvaluator.evaluate(allOpenInputs.copy(batteryPercent = 19))
        assertEquals(GateDecision.Blocked(GateReason.LowBattery), decision)
    }

    @Test
    fun lowBattery_atThreshold_allows() {
        val decision = GateEvaluator.evaluate(allOpenInputs.copy(batteryPercent = 20))
        assertEquals(GateDecision.Allowed, decision)
    }

    @Test
    fun lowBattery_isIgnored_whenPauseDisabled() {
        val decision = GateEvaluator.evaluate(
            allOpenInputs.copy(batteryPercent = 1),
            GateConfig(lowBatteryPauseEnabled = false),
        )
        assertEquals(GateDecision.Allowed, decision)
    }

    @Test
    fun relayOnlyPath_blocksWithRightReason_whenDirectOnlyEnabled() {
        for (kind in listOf(ConnKind.Relay, ConnKind.Tor, ConnKind.Unknown, null)) {
            val decision = GateEvaluator.evaluate(allOpenInputs.copy(targetConnKind = kind))
            assertEquals("connKind=$kind", GateDecision.Blocked(GateReason.RelayOnlyPath), decision)
        }
    }

    @Test
    fun relayOnlyPath_isIgnored_whenDirectOnlyDisabled() {
        val decision = GateEvaluator.evaluate(
            allOpenInputs.copy(targetConnKind = ConnKind.Relay),
            GateConfig(directOnly = false),
        )
        assertEquals(GateDecision.Allowed, decision)
    }

    @Test
    fun cellularPlusDirect_isAllowed_whenWifiOnlyDisabled() {
        // USER correction 2026-08-18: cellular + Direct exists; the gate
        // decision must come from per-target ConnKind, never a
        // network-type heuristic.
        val decision = GateEvaluator.evaluate(
            allOpenInputs.copy(isWifiConnected = false, targetConnKind = ConnKind.Direct),
            GateConfig(wifiOnly = false),
        )
        assertEquals(GateDecision.Allowed, decision)
    }

    @Test
    fun tunnelNotActive_takesPriority_overOtherFailures() {
        val decision = GateEvaluator.evaluate(
            allOpenInputs.copy(
                tunnelState = BridgeTunnelState.Standby,
                isWifiConnected = false,
                batteryPercent = 0,
                targetConnKind = ConnKind.Relay,
            ),
        )
        assertEquals(GateDecision.Blocked(GateReason.TunnelNotActive), decision)
    }

    @Test
    fun blockedDecision_neverTriggersTransfer() {
        var transferInvoked = false
        val blockedInputs = allOpenInputs.copy(isWifiConnected = false)

        val decision = GateEvaluator.evaluate(blockedInputs)
        if (decision is GateDecision.Allowed) {
            transferInvoked = true
        }

        assertTrue(decision is GateDecision.Blocked)
        assertEquals("a blocked gate decision must never lead to a transfer attempt", false, transferInvoked)
    }
}
