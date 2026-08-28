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
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
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
