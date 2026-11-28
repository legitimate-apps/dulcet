plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.sqldelight) apply false
}

// The live Android suites (`-Pdulcet.productionSearchConformance`) share one account on one
// disposable server, and per-user state such as a favourite is visible to every client of it.
// Configuration caching lets Gradle run the phone and TV test tasks at the same time, so each live
// test task takes this service and only one of them runs at once.
abstract class DisposableServerAccount : BuildService<BuildServiceParameters.None>
gradle.sharedServices.registerIfAbsent("disposableServerAccount", DisposableServerAccount::class) {
    maxParallelUsages.set(1)
}

// Robolectric downloads its Android runtime jars (android-all-instrumented, one per SDK level) while
// tests run, outside Gradle's dependency cache, from repo1.maven.org by default. Hosted runners get
// HTTP 429 from it: on 2026-11-28 three pull requests' core-build jobs failed 35 Android host tests
// each in setup, with no test body run. Google's mirror of Maven Central serves the same artifacts.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty(
            "robolectric.dependency.repo.url",
            "https://maven-central.storage-download.googleapis.com/maven2",
        )
    }
}
