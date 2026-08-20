// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SYNC-009 ACCEPTANCE: "Transfer anyway?" relaxes exactly the one knob that
 * corresponds to the blocking reason, never more, and never for a reason
 * that has no knob at all.
 */
class GateOverrideTest {

    private val allOn = GateConfig(
        wifiOnly = true,
        directOnly = true,
        chargingRequired = true,
        lowBatteryPauseEnabled = true,
    )

    @Test
    fun notOnWifi_relaxesOnlyWifiOnly() {
        val relaxed = relaxedGateConfig(GateReason.NotOnWifi, allOn)
        assertEquals(allOn.copy(wifiOnly = false), relaxed)
    }

    @Test
    fun chargingRequired_relaxesOnlyChargingRequired() {
        val relaxed = relaxedGateConfig(GateReason.ChargingRequired, allOn)
        assertEquals(allOn.copy(chargingRequired = false), relaxed)
    }

    @Test
    fun lowBattery_relaxesOnlyLowBatteryPauseEnabled() {
        val relaxed = relaxedGateConfig(GateReason.LowBattery, allOn)
        assertEquals(allOn.copy(lowBatteryPauseEnabled = false), relaxed)
    }

    @Test
    fun relayOnlyPath_relaxesOnlyDirectOnly() {
        val relaxed = relaxedGateConfig(GateReason.RelayOnlyPath, allOn)
        assertEquals(allOn.copy(directOnly = false), relaxed)
    }

    @Test
    fun tunnelNotActive_hasNoKnob_isNull() {
        assertNull(relaxedGateConfig(GateReason.TunnelNotActive, allOn))
    }

    @Test
    fun targetUnreachable_hasNoKnob_isNull() {
        assertNull(relaxedGateConfig(GateReason.TargetUnreachable, allOn))
    }
}
