plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // SYNC-001: the Kotlin 2.0+ K2-era Compose compiler plugin -- decouples
    // the Compose compiler's own version from Kotlin's. Version-matched to
    // the kotlin-android plugin above (same proven combination as
    // tetron-mobile).
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}