plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
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
