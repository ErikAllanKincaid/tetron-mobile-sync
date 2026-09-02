// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.scope

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SYNC-012 ACCEPTANCE: the pure scope-selection / backlog-aggregation
 * functions the `--files-from` builder and the Settings estimate share.
 */
class ScopeSelectionTest {

    private fun jpg(name: String, mb: Long) = MediaEntry(name, "image/jpeg", mb * 1024 * 1024)
    private fun dng(name: String, mb: Long) = MediaEntry(name, null, mb * 1024 * 1024) // OEM: no MIME
    private fun mp4(name: String, mb: Long) = MediaEntry(name, "video/mp4", mb * 1024 * 1024)
    private fun other(name: String, mb: Long) = MediaEntry(name, "image/webp", mb * 1024 * 1024)

    private val roll = listOf(
        jpg("IMG_1.jpg", 5), jpg("IMG_2.jpg", 5),
        dng("IMG_1.dng", 50), dng("IMG_2.dng", 50),
        mp4("VID_1.mp4", 1200), mp4("VID_2.mp4", 300),
        other("MOTION.webp", 8),
    )

    // --- selectInScope ---

    @Test fun everything_on_includes_everything() {
        val sel = selectInScope(roll, BackupScope(includeRaw = true))
        assertEquals(roll.map { it.displayName }, sel.includedNames)
        assertEquals(0, sel.oversizeSkippedCount)
    }

    @Test fun sync013_default_scope_flags() {
        val d = BackupScope()
        assertEquals(true, d.includeJpeg)
        assertEquals(true, d.includeHeic)
        assertEquals(true, d.includeVideos)
        assertEquals(true, d.includeOtherFiles)
        assertEquals(false, d.includeRaw)
        assertEquals(false, d.includePictures)
        assertEquals(null, d.maxSizeBytes)
        assertEquals(null, d.bwlimitKib)
    }

    @Test fun sync013_default_scope_drops_raw() {
        // SYNC-013: the new-install default has Raw OFF -- the two .dng
        // siblings are excluded, everything else in the roll stays.
        val sel = selectInScope(roll, BackupScope())
        assertEquals(
            listOf("IMG_1.jpg", "IMG_2.jpg", "VID_1.mp4", "VID_2.mp4", "MOTION.webp"),
            sel.includedNames,
        )
        assertEquals(0, sel.oversizeSkippedCount)
    }

    @Test fun users_example_jpg_only_no_video() {
        // USER 2026-08-28: keep the .jpg, drop the ~50 MB .dng siblings and
        // the videos.
        val scope = BackupScope(includeRaw = false, includeVideos = false)
        val sel = selectInScope(roll, scope)
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "MOTION.webp"), sel.includedNames)
        assertEquals(0, sel.oversizeSkippedCount)
    }

    @Test fun oversize_drops_are_counted_type_drops_are_not() {
        val scope = BackupScope(includeRaw = false, maxSizeBytes = 1024L * 1024 * 1024) // 1 GiB
        val sel = selectInScope(roll, scope)
        // .dng excluded by type (not counted); VID_1 (1200 MB) over the cap.
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "VID_2.mp4", "MOTION.webp"), sel.includedNames)
        assertEquals(1, sel.oversizeSkippedCount)
    }

    @Test fun other_files_off_restricts_to_known_sets() {
        val sel = selectInScope(roll, BackupScope(includeRaw = true, includeOtherFiles = false))
        assertEquals(false, sel.includedNames.contains("MOTION.webp"))
        assertEquals(roll.size - 1, sel.includedNames.size)
    }

    @Test fun empty_input_is_an_empty_selection_not_a_failure() {
        val sel = selectInScope(emptyList(), BackupScope())
        assertEquals(emptyList<String>(), sel.includedNames)
        assertEquals(0, sel.oversizeSkippedCount)
    }

    // --- estimateBacklog ---

    @Test fun estimate_totals_split_photos_and_videos() {
        val est = estimateBacklog(roll, BackupScope())
        assertEquals(7, est.totalCount)
        assertEquals(5, est.photoCount) // 2 jpg + 2 dng + 1 webp
        assertEquals(2, est.videoCount)
        assertEquals(roll.sumOf { it.sizeBytes }, est.totalBytes)
    }

    @Test fun estimate_included_reflects_the_scope() {
        val scope = BackupScope(includeRaw = false, includeVideos = false)
        val est = estimateBacklog(roll, scope)
        assertEquals(3, est.includedCount) // 2 jpg + 1 webp
        assertEquals((5L + 5 + 8) * 1024 * 1024, est.includedBytes)
        assertEquals(
            listOf(MediaKind.Jpeg, MediaKind.Other),
            est.includedByKind.map { it.kind },
        )
        assertEquals(2, est.includedByKind.first { it.kind == MediaKind.Jpeg }.count)
    }

    @Test fun estimate_excluded_by_kind_lists_toggled_off_types_with_totals() {
        val scope = BackupScope(includeRaw = false, includeVideos = false)
        val est = estimateBacklog(roll, scope)
        val raw = est.excludedByKind.first { it.kind == MediaKind.Raw }
        assertEquals(2, raw.count)
        assertEquals(100L * 1024 * 1024, raw.bytes)
        val video = est.excludedByKind.first { it.kind == MediaKind.Video }
        assertEquals(2, video.count)
    }

    @Test fun estimate_oversize_bucket_is_separate_from_excluded_by_kind() {
        val scope = BackupScope(maxSizeBytes = 1024L * 1024 * 1024) // 1 GiB
        val est = estimateBacklog(roll, scope)
        assertEquals(1, est.oversizeCount) // VID_1 only
        assertEquals(1200L * 1024 * 1024, est.oversizeBytes)
        // VID_1 is not also tallied under an excluded Video kind
        assertEquals(true, est.excludedByKind.none { it.kind == MediaKind.Video })
    }

    @Test fun estimate_largest_included_is_longest_first_and_capped() {
        val est = estimateBacklog(roll, BackupScope(), largestN = 2)
        assertEquals(listOf("VID_1.mp4", "VID_2.mp4"), est.largestIncluded.map { it.displayName })
    }

    @Test fun estimate_of_empty_roll_is_empty() {
        val est = estimateBacklog(emptyList(), BackupScope())
        assertEquals(BacklogEstimate.EMPTY, est)
    }
}
