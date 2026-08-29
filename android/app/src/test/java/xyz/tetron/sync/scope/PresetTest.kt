// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.scope

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SYNC-012 ACCEPTANCE: the preset<->scope round trip and Custom-on-edit
 * detection. Mixes per decision B2.
 */
class PresetTest {

    @Test fun everything_is_the_all_on_default_scope() {
        assertEquals(BackupScope(), scopeForPreset(Preset.Everything, BackupScope()))
    }

    @Test fun photos_only_drops_videos_and_keeps_raw() {
        val s = scopeForPreset(Preset.PhotosOnly, BackupScope())
        assertEquals(false, s.includeVideos)
        assertEquals(true, s.includeRaw)
        assertEquals(true, s.includeJpeg)
        assertEquals(true, s.includeHeic)
        assertEquals(true, s.includeOtherFiles)
        assertEquals(null, s.maxSizeBytes)
    }

    @Test fun lean_drops_raw_caps_size_and_limits_bandwidth() {
        val s = scopeForPreset(Preset.Lean, BackupScope())
        assertEquals(false, s.includeRaw)
        assertEquals(true, s.includeVideos)
        assertEquals(LEAN_MAX_SIZE_BYTES, s.maxSizeBytes)
        assertEquals(LEAN_BWLIMIT_KIB, s.bwlimitKib)
    }

    @Test fun custom_is_not_a_template_it_returns_current() {
        val odd = BackupScope(includeJpeg = false, includeHeic = false, maxSizeBytes = 42)
        assertEquals(odd, scopeForPreset(Preset.Custom, odd))
    }

    // --- presetOf round trip ---

    @Test fun presetOf_recognises_each_builtin() {
        assertEquals(Preset.Everything, presetOf(scopeForPreset(Preset.Everything, BackupScope())))
        assertEquals(Preset.PhotosOnly, presetOf(scopeForPreset(Preset.PhotosOnly, BackupScope())))
        assertEquals(Preset.Lean, presetOf(scopeForPreset(Preset.Lean, BackupScope())))
    }

    @Test fun presetOf_default_scope_is_everything() {
        assertEquals(Preset.Everything, presetOf(BackupScope()))
    }

    @Test fun editing_any_field_flips_to_custom() {
        val lean = scopeForPreset(Preset.Lean, BackupScope())
        // user turns the bandwidth limit off while on Lean
        assertEquals(Preset.Custom, presetOf(lean.copy(bwlimitKib = null)))
        // user turns a type back on
        assertEquals(Preset.Custom, presetOf(lean.copy(includeRaw = true)))
        // user tweaks the cap
        assertEquals(Preset.Custom, presetOf(lean.copy(maxSizeBytes = 500_000)))
    }

    @Test fun custom_scope_that_happens_to_match_a_builtin_reports_that_builtin() {
        // there is no hidden "was Custom" state -- equality is the identity
        val rebuilt = BackupScope(includeVideos = false)
        assertEquals(Preset.PhotosOnly, presetOf(rebuilt))
    }
}
