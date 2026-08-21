plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// SYNC-001: this app's own commit, mechanically derived (never hand-typed,
// so it cannot drift from what is actually built) -- same traceability bar
// as tetron-mobile's MOBILE-019 mechanism. Run with `rootDir` (the
// `android/` Gradle root) as the working directory -- git walks up from
// there to find this repo's own `.git` regardless of Gradle's root not
// being the repo root.
//
// NOT `git describe --always --dirty` (tetron-mobile's MOBILE-019 note
// applies verbatim): `--always` prefers the nearest tag over a bare SHA
// when one is reachable. `rev-parse` + a separate dirty check can not be
// preempted by a tag match either way.
fun runGit(vararg args: String): String =
    ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .let { it.waitFor(); it.inputStream.bufferedReader().readText().trim() }

val gitSha: String =
    try {
        val sha = runGit("rev-parse", "--short=7", "HEAD")
        val dirty = runGit("status", "--porcelain").isNotEmpty()
        when {
            sha.isEmpty() -> "unknown"
            dirty -> "$sha-dirty"
            else -> sha
        }
    } catch (e: Exception) {
        "unknown"
    }

android {
    namespace = "xyz.tetron.sync"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Provisional package id; the naming decision is an open item
        // (plan §Repo/naming, spec/sync.py "Still open").
        applicationId = "xyz.tetron.sync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = System.getenv("APP_VERSION_NAME") ?: "dev"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // SYNC-001: instrumented smoke test harness (first androidTest in
        // this repo; SYNC-003/005/011 grow it).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // SYNC-001: the two live reference-test ABIs -- LG V40
            // (arm64-v8a) and emulator AVDs (x86_64), mirroring tetron-mobile.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // SYNC-009: per-screen state holders (StateFlow) + collectAsStateWithLifecycle.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // SYNC-009: the four-screen shell (Home/Progress/History/Settings).
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // SYNC-001: UniFFI's generated Kotlin bindings load the .so via JNA,
    // not raw JNI.
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    // SYNC-006: v1 trigger layer's periodic job (decision #14).
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")

    // SYNC-001: instrumented smoke test deps -- runner + JUnit4.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    // SYNC-006: WorkManager's own test harness -- verifies a request was
    // enqueued without waiting on real OS scheduling.
    androidTestImplementation("androidx.work:work-testing:2.10.0")
    androidTestImplementation("junit:junit:4.13.2")
}