import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.licensee)
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
        withHostTestBuilder {}
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()

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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

licensee {
    allow("Apache-2.0")
}
