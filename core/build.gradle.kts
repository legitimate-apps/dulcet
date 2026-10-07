import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.licensee)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("DulcetDatabase") {
            packageName.set("com.legitimateapps.dulcet.database")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "com.legitimateapps.dulcet.core"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64 {
        // CI names the simulator the core's own reader conformance tests run on, as it does for
        // :core-conformance; without it Gradle picks a device type by name, which an image may lack.
        providers.gradleProperty("dulcet.iosSimulatorUdid").orNull?.let { simulatorUdid ->
            testRuns["test"].deviceId = simulatorUdid
        }
    }
    tvosArm64()
    tvosSimulatorArm64 {
        // The same for the tvOS run of the reader's conformance tests.
        providers.gradleProperty("dulcet.tvosSimulatorUdid").orNull?.let { simulatorUdid ->
            testRuns["test"].deviceId = simulatorUdid
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "DulcetCore"
            isStatic = true
            binaryOption("bundleId", "com.legitimateapps.dulcet.core")
            freeCompilerArgs += listOf(
                "-Xoverride-konan-properties=minVersion.macos=${libs.versions.macos.deployment.get()}",
                "-Xoverride-konan-properties=minVersion.ios=${libs.versions.ios.deployment.get()}",
                "-Xoverride-konan-properties=minVersion.tvos=${libs.versions.tvos.deployment.get()}",
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.sqldelight.runtime)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            api(libs.media3.exoplayer)
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.android.driver)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

licensee {
    allow("Apache-2.0")
    allowUrl("https://opensource.org/license/mit")
}

// A failing test must say WHY in the console. Gradle's default console format is SHORT, which
// prints only "java.lang.AssertionError at File.kt:109": the assertion message survives only in
// the JUnit XML, so reading a red CI run meant downloading an artifact. FULL prints the message,
// the causes and the stack (truncated at the test entry point). Failures only, and no standard
// streams, so a green run's log is unchanged. AbstractTestTask, not Test, where Kotlin/Native
// test tasks exist too (see core-conformance/build.gradle.kts for that trap).
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
