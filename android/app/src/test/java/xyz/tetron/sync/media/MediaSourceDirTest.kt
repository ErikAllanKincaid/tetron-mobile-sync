// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.media

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.tetron.sync.scope.BackupScope

/**
 * SYNC-013 ACCEPTANCE (pure parts): which directories a scope activates on
 * a given OS level, and the exact-match SQL each produces. `MediaStore` /
 * `Build` references here are inlined compile-time constants, so this runs
 * under plain JUnit.
 */
class MediaSourceDirTest {

    private val api29 = Build.VERSION_CODES.Q

    @Test fun camera_is_always_active_pictures_only_when_toggled() {
        val off = activeSourceDirs(BackupScope(includePictures = false), api29)
        assertEquals(listOf("DCIM/Camera/"), off.map { it.relativePath })

        val on = activeSourceDirs(BackupScope(includePictures = true), api29)
        assertEquals(listOf("DCIM/Camera/", "Pictures/"), on.map { it.relativePath })
    }

    @Test fun staging_order_is_camera_then_pictures() {
        val dirs = activeSourceDirs(BackupScope(includePictures = true), api29)
        assertEquals("DCIM/Camera/", dirs.first().relativePath)
        assertEquals("Pictures/", dirs.last().relativePath)
    }

    @Test fun a_dir_below_its_min_api_is_dropped() {
        val futureDir = MediaSourceDir(
            relativePath = "Recordings/",
            mediaTypes = listOf(1, 3),
            minApiLevel = Build.VERSION_CODES.S, // 31
            enabled = { true },
        )
        val all = SOURCE_DIRS + futureDir
        assertTrue(activeSourceDirs(BackupScope(), apiLevel = 30, all = all).none { it.relativePath == "Recordings/" })
        assertTrue(activeSourceDirs(BackupScope(), apiLevel = 31, all = all).any { it.relativePath == "Recordings/" })
    }

    @Test fun selection_is_an_exact_relative_path_match_not_a_prefix() {
        val (selection, args) = mediaSourceSelection(SOURCE_DIRS.first { it.relativePath == "Pictures/" })
        // `=` not LIKE -> Pictures/Screenshots/ can never match.
        assertTrue(selection.contains("relative_path = ?"))
        assertTrue(!selection.contains("LIKE"))
        assertEquals("Pictures/", args.first())
    }

    @Test fun selection_has_one_placeholder_per_media_type() {
        val (selection, args) = mediaSourceSelection(SOURCE_DIRS.first())
        assertTrue(selection.contains("media_type IN (?, ?)"))
        // relative-path arg + one per media type
        assertEquals(1 + SOURCE_DIRS.first().mediaTypes.size, args.size)
    }
}
