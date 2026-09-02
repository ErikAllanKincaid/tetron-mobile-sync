// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.scope

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SYNC-012 ACCEPTANCE: the [ScopeFilter] decision for every toggle state
 * (each type individually excluded with the right reason, "Other files" OFF
 * restricting to the known sets, the oversize path, MIME-vs-extension
 * precedence).
 */
class ScopeFilterTest {

    // every include ON, no caps -- Raw is off in the SYNC-013 default, so
    // set it explicitly to keep this an "everything permitted" baseline.
    private val all = BackupScope(includeRaw = true)

    // --- classify: MIME first (decision A3) ---

    @Test fun classify_jpeg_by_mime() {
        assertEquals(MediaKind.Jpeg, ScopeFilter.classify("IMG_0001.xyz", "image/jpeg"))
    }

    @Test fun classify_heic_by_mime_including_sequence() {
        assertEquals(MediaKind.Heic, ScopeFilter.classify("x", "image/heif"))
        assertEquals(MediaKind.Heic, ScopeFilter.classify("x", "image/heic-sequence"))
    }

    @Test fun classify_dng_by_mime() {
        assertEquals(MediaKind.Raw, ScopeFilter.classify("shot", "image/x-adobe-dng"))
    }

    @Test fun classify_video_by_mime_family() {
        assertEquals(MediaKind.Video, ScopeFilter.classify("clip", "video/quicktime"))
        assertEquals(MediaKind.Video, ScopeFilter.classify("clip", "video/x-matroska"))
    }

    // --- classify: extension fallback when MIME is absent/generic ---

    @Test fun classify_raw_by_extension_when_mime_generic() {
        // OEMs commonly report a bare or generic MIME for raw.
        assertEquals(MediaKind.Raw, ScopeFilter.classify("DSC1234.NEF", "image/*"))
        assertEquals(MediaKind.Raw, ScopeFilter.classify("DSC1234.arw", null))
        assertEquals(MediaKind.Raw, ScopeFilter.classify("DSC1234.cr3", ""))
    }

    @Test fun classify_jpeg_by_extension_case_insensitive() {
        assertEquals(MediaKind.Jpeg, ScopeFilter.classify("PHOTO.JPG", null))
        assertEquals(MediaKind.Jpeg, ScopeFilter.classify("photo.jpeg", null))
    }

    @Test fun classify_unknown_is_other() {
        assertEquals(MediaKind.Other, ScopeFilter.classify("motion.webp", "image/webp"))
        assertEquals(MediaKind.Other, ScopeFilter.classify("note.txt", "text/plain"))
        assertEquals(MediaKind.Other, ScopeFilter.classify("noextension", null))
    }

    @Test fun classify_honours_a_custom_set() {
        val sets = MediaTypeSets.DEFAULT.copy(raw = MediaTypeSets.DEFAULT.raw + "x3f")
        assertEquals(MediaKind.Raw, ScopeFilter.classify("SIGMA.x3f", null, sets))
        // still Other under the default set
        assertEquals(MediaKind.Other, ScopeFilter.classify("SIGMA.x3f", null))
    }

    // --- decide: type toggles ---

    @Test fun decide_included_when_all_on() {
        assertEquals(ScopeDecision.Included, ScopeFilter.decide("a.jpg", "image/jpeg", 10, all))
    }

    @Test fun decide_each_type_excluded_with_its_kind() {
        val cases = mapOf(
            ("a.jpg" to "image/jpeg") to MediaKind.Jpeg,
            ("a.heic" to "image/heic") to MediaKind.Heic,
            ("a.dng" to "image/x-adobe-dng") to MediaKind.Raw,
            ("a.mp4" to "video/mp4") to MediaKind.Video,
            ("a.webp" to "image/webp") to MediaKind.Other,
        )
        for ((file, kind) in cases) {
            val scope = when (kind) {
                MediaKind.Jpeg -> all.copy(includeJpeg = false)
                MediaKind.Heic -> all.copy(includeHeic = false)
                MediaKind.Raw -> all.copy(includeRaw = false)
                MediaKind.Video -> all.copy(includeVideos = false)
                MediaKind.Other -> all.copy(includeOtherFiles = false)
            }
            assertEquals(
                "$file should be ExcludedType($kind)",
                ScopeDecision.ExcludedType(kind),
                ScopeFilter.decide(file.first, file.second, 10, scope),
            )
        }
    }

    @Test fun decide_other_files_off_still_allows_known_types() {
        val scope = all.copy(includeOtherFiles = false)
        assertEquals(ScopeDecision.Included, ScopeFilter.decide("a.jpg", "image/jpeg", 10, scope))
        assertEquals(ScopeDecision.Included, ScopeFilter.decide("a.dng", null, 10, scope))
        assertEquals(
            ScopeDecision.ExcludedType(MediaKind.Other),
            ScopeFilter.decide("a.webp", "image/webp", 10, scope),
        )
    }

    // --- decide: size cap ---

    @Test fun decide_no_cap_by_default() {
        assertEquals(
            ScopeDecision.Included,
            ScopeFilter.decide("big.mp4", "video/mp4", 50L * 1024 * 1024 * 1024, all),
        )
    }

    @Test fun decide_oversize_excluded_strictly_over_cap() {
        val scope = all.copy(maxSizeBytes = 1_000)
        assertEquals(ScopeDecision.Included, ScopeFilter.decide("a.mp4", "video/mp4", 1_000, scope))
        assertEquals(
            ScopeDecision.ExcludedOversize,
            ScopeFilter.decide("a.mp4", "video/mp4", 1_001, scope),
        )
    }

    @Test fun decide_type_off_takes_precedence_over_oversize() {
        val scope = all.copy(includeVideos = false, maxSizeBytes = 1_000)
        assertEquals(
            ScopeDecision.ExcludedType(MediaKind.Video),
            ScopeFilter.decide("a.mp4", "video/mp4", 5_000, scope),
        )
    }
}
