// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYNC-010: the device label is the first path component under the receiver
 * module, so it must validate to exactly one safe path segment.
 */
class DeviceLabelTest {

    private fun valid(raw: String) = DeviceLabel.validate(raw) as? DeviceLabel.Result.Valid

    @Test fun plain_label_is_valid_and_untouched() {
        assertEquals("pixel-7", valid("pixel-7")?.label)
    }

    @Test fun surrounding_whitespace_is_trimmed() {
        assertEquals("pixel-7", valid("  pixel-7 ")?.label)
    }

    @Test fun empty_or_blank_is_rejected() {
        assertNull(valid(""))
        assertNull(valid("   "))
    }

    @Test fun slash_is_rejected() {
        assertNull(valid("dcim/camera"))
        assertNull(valid("/abs"))
    }

    @Test fun dot_and_dotdot_are_rejected() {
        assertNull(valid("."))
        assertNull(valid(".."))
    }

    @Test fun leading_dot_is_rejected() {
        assertNull(valid(".hidden"))
    }

    @Test fun control_characters_are_rejected() {
        assertNull(valid("na" + '\n' + "me"))
        assertNull(valid("na" + '\t' + "me"))
    }

    @Test fun a_space_is_allowed_inside_the_label() {
        assertEquals("my phone", valid("my phone")?.label)
    }

    @Test fun over_length_is_rejected() {
        assertNull(valid("x".repeat(DeviceLabel.MAX_LENGTH + 1)))
        assertEquals(
            "x".repeat(DeviceLabel.MAX_LENGTH),
            valid("x".repeat(DeviceLabel.MAX_LENGTH))?.label,
        )
    }

    @Test fun normalizedOrNull_mirrors_validate() {
        assertEquals("phone", DeviceLabel.normalizedOrNull(" phone "))
        assertNull(DeviceLabel.normalizedOrNull("a/b"))
    }

    @Test fun generated_fallback_is_itself_a_valid_label() {
        val a = DeviceLabel.generateFallback()
        val b = DeviceLabel.generateFallback()
        assertTrue(a.startsWith("phone-"))
        assertEquals(a, valid(a)?.label)
        assertNotEquals(a, b)
    }

    @Test fun generated_fallback_prefers_the_mesh_hostname() {
        assertEquals("my-pixel", DeviceLabel.generateFallback("  my-pixel  "))
        // Unusable hostname -> the phone-<hex> fallback still applies.
        assertTrue(DeviceLabel.generateFallback("bad/name").startsWith("phone-"))
        assertTrue(DeviceLabel.generateFallback("").startsWith("phone-"))
        assertTrue(DeviceLabel.generateFallback(null).startsWith("phone-"))
    }
}
