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
