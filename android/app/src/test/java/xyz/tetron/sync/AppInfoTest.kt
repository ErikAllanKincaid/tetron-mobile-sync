// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun describeEngine_formats_version() {
        assertEquals("tetron-mobile-sync engine 0.1.0", AppInfo.describeEngine("0.1.0"))
    }
}