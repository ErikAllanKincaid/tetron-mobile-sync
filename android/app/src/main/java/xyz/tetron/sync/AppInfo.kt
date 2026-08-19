// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync

/**
 * SYNC-001: tiny pure-Kotlin formatting helper, the one piece of
 * scaffold logic a JVM unit test can exercise without the .so. Kept
 * deliberately trivial; grows with the SYNC-009 UI pass.
 */
object AppInfo {
    fun describeEngine(version: String): String = "tetron-mobile-sync engine $version"
}