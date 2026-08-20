// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYNC-008 ACCEPTANCE (Android-side): path resolution + partial-access
 * grant detection from a mocked [MediaPermissionState] -- no Android
 * dependency, matching [xyz.tetron.sync.gates.GateEvaluatorTest]'s bar.
 */
class MediaAccessTest {

    private val none = MediaPermissionState(
        readMediaImagesGranted = false,
        readMediaVideoGranted = false,
        readMediaVisualUserSelectedGranted = false,
        readExternalStorageGranted = false,
    )

    // -- resolveMediaAccessGrant: pre-33 --

    @Test
    fun preApi33_readExternalStorageGranted_isFull() {
        val state = none.copy(readExternalStorageGranted = true)
        assertEquals(MediaAccessGrant.Full, resolveMediaAccessGrant(state, apiLevel = 29))
    }

    @Test
    fun preApi33_noGrant_isNotGranted() {
        assertEquals(MediaAccessGrant.NotGranted, resolveMediaAccessGrant(none, apiLevel = 29))
    }

    @Test
    fun preApi33_mediaImagesGrantedAlone_isIgnored_isNotGranted() {
        // Pre-33 devices cannot even grant READ_MEDIA_IMAGES; a caller that
        // somehow sets it must not be honoured at this API level.
        val state = none.copy(readMediaImagesGranted = true, readMediaVideoGranted = true)
        assertEquals(MediaAccessGrant.NotGranted, resolveMediaAccessGrant(state, apiLevel = 29))
    }

    // -- resolveMediaAccessGrant: API 33 --

    @Test
    fun api33_imagesAndVideoGranted_isFull() {
        val state = none.copy(readMediaImagesGranted = true, readMediaVideoGranted = true)
        assertEquals(MediaAccessGrant.Full, resolveMediaAccessGrant(state, apiLevel = 33))
    }

    @Test
    fun api33_visualUserSelected_doesNotExistYet_isNotGranted() {
        // READ_MEDIA_VISUAL_USER_SELECTED was added in API 34; a caller
        // setting it on a 33 device must not be read as Partial.
        val state = none.copy(readMediaVisualUserSelectedGranted = true)
        assertEquals(MediaAccessGrant.NotGranted, resolveMediaAccessGrant(state, apiLevel = 33))
    }

    @Test
    fun api33_noGrant_isNotGranted() {
        assertEquals(MediaAccessGrant.NotGranted, resolveMediaAccessGrant(none, apiLevel = 33))
    }

    // -- resolveMediaAccessGrant: API 34+ --

    @Test
    fun api34_imagesAndVideoGranted_isFull() {
        val state = none.copy(readMediaImagesGranted = true, readMediaVideoGranted = true)
        assertEquals(MediaAccessGrant.Full, resolveMediaAccessGrant(state, apiLevel = 34))
    }

    @Test
    fun api34_visualUserSelectedOnly_isPartial() {
        val state = none.copy(readMediaVisualUserSelectedGranted = true)
        assertEquals(MediaAccessGrant.Partial, resolveMediaAccessGrant(state, apiLevel = 34))
    }

    @Test
    fun api34_fullGrant_takesPriorityOverVisualUserSelected() {
        val state = none.copy(
            readMediaImagesGranted = true,
            readMediaVideoGranted = true,
            readMediaVisualUserSelectedGranted = true,
        )
        assertEquals(MediaAccessGrant.Full, resolveMediaAccessGrant(state, apiLevel = 34))
    }

    @Test
    fun api34_noGrant_isNotGranted() {
        assertEquals(MediaAccessGrant.NotGranted, resolveMediaAccessGrant(none, apiLevel = 34))
    }

    // -- resolveSourcePath / resolveMediaAccessState --

    @Test
    fun notGranted_neverResolvesAPath_regardlessOfDirectory() {
        val dir = createTempDir(prefix = "camera").apply { deleteOnExit() }
        assertNull(resolveSourcePath(MediaAccessGrant.NotGranted, dir))
    }

    @Test
    fun fullGrant_missingDirectory_resolvesNull() {
        val dir = File(createTempDir(prefix = "camera").apply { deleteOnExit() }, "does-not-exist")
        assertNull(resolveSourcePath(MediaAccessGrant.Full, dir))
    }

    @Test
    fun fullGrant_existingDirectory_resolvesItsPath() {
        val dir = createTempDir(prefix = "camera").apply { deleteOnExit() }
        assertEquals(dir.path, resolveSourcePath(MediaAccessGrant.Full, dir))
    }

    @Test
    fun partialGrant_existingDirectory_stillResolvesAPath() {
        // A partial grant backs up whatever subset was selected -- it does
        // not block the run outright (spec/sync.py SYNC-008).
        val dir = createTempDir(prefix = "camera").apply { deleteOnExit() }
        assertEquals(dir.path, resolveSourcePath(MediaAccessGrant.Partial, dir))
    }

    @Test
    fun resolveMediaAccessState_partial_showsWarning_full_doesNot() {
        val dir = createTempDir(prefix = "camera").apply { deleteOnExit() }
        val partialState = none.copy(readMediaVisualUserSelectedGranted = true)
        val fullState = none.copy(readMediaImagesGranted = true, readMediaVideoGranted = true)

        val partial = resolveMediaAccessState(partialState, apiLevel = 34, directory = dir)
        val full = resolveMediaAccessState(fullState, apiLevel = 34, directory = dir)

        assertTrue(partial.showPartialAccessWarning)
        assertEquals(dir.path, partial.sourcePath)
        assertFalse(full.showPartialAccessWarning)
        assertEquals(dir.path, full.sourcePath)
    }

    @Test
    fun resolveMediaAccessState_notGranted_noPath_noWarning() {
        val dir = createTempDir(prefix = "camera").apply { deleteOnExit() }
        val result = resolveMediaAccessState(none, apiLevel = 34, directory = dir)
        assertEquals(MediaAccessGrant.NotGranted, result.grant)
        assertNull(result.sourcePath)
        assertFalse(result.showPartialAccessWarning)
    }
}
