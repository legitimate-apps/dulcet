import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Dulcet Server: Dulcet's own OpenSubsonic server (spec §29). A plain JVM application; it shares
// the build and the license with the client but never depends on :core, and nothing in the client
// depends on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.licensee)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.legitimateapps.dulcet.server.MainKt")
    applicationName = "dulcet-server"
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.simple)
    implementation("at.favre.lib:bcrypt:0.10.2")

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allowUrl("https://opensource.org/license/mit")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.processResources {
    from(rootProject.file("docs/dulcet-api/mcp-tools.json")) {
        into("dulcet-api")
    }
    from(rootProject.file("docs/dulcet-api/openapi.yaml")) {
        into("dulcet-api")
    }
}
