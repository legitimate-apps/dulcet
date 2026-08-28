import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    macosArm64()
    iosSimulatorArm64 {
        providers.gradleProperty("dulcet.iosSimulatorUdid").orNull?.let { simulatorUdid ->
            testRuns["test"].deviceId = simulatorUdid
        }
    }
    tvosSimulatorArm64 {
        providers.gradleProperty("dulcet.tvosSimulatorUdid").orNull?.let { simulatorUdid ->
            testRuns["test"].deviceId = simulatorUdid
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(project(":core"))
            implementation(kotlin("test"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
        appleTest.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// A conformance failure is a measurement disagreeing with the server, so the assertion message IS
// the finding -- it carries the observed status, byte counts or ratio. Gradle's default console
// output prints only "AssertionError at <file>:<line>", which on a CI runner leaves nothing to
// diagnose from: OBSERVED 2026-08-28, a CONF-14a failure in core-ci reported exactly that and the
// cause could not be determined from the log at all. Print the message and stack, and keep the
// JUnit XML as an artifact (see .github/workflows/core-ci.yml).
//
// AbstractTestTask, not Test: `Test` is the JVM task type only, so the first version of this block
// left the Kotlin/Native test tasks reporting bare "AssertionError at <file>:<line>" exactly as
// before. OBSERVED 2026-08-28 when :core-conformance:macosArm64Test failed three controls and the
// log carried no message for any of them, while the JVM run of the same suite was fully legible.
// AbstractTestTask is the common supertype of the JVM, native and JS test tasks.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showExceptions = true
    }
}
