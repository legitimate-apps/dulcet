import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The public Dulcet API v1 conformance suite (docs/dulcet-api/CONFORMANCE.md). A standalone
// command-line runner that anyone points at a base URL. It never depends on :server or :core:
// a third-party server or client must be able to build and run it from this directory alone.
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
    mainClass.set("com.legitimateapps.dulcet.apiconformance.MainKt")
    applicationName = "dulcet-api-conformance"
}

dependencies {
    implementation(libs.json.schema.validator)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allowUrl("https://opensource.org/license/mit")
}

// The contract is the source of truth: the suite validates every response against the exact
// openapi.yaml in this repository, copied into the jar at build time, never a hand-kept copy.
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("docs/dulcet-api/openapi.yaml")) {
        into("dulcet-api")
    }
    from(rootProject.layout.projectDirectory.file("docs/dulcet-api/mcp-tools.json")) {
        into("dulcet-api")
    }
}

interface ArchiveAccess {
    @get:Inject
    val archives: ArchiveOperations
}

val archiveAccess = objects.newInstance<ArchiveAccess>()
val runtimeJars = configurations.runtimeClasspath

// One self-contained jar: `java -jar dulcet-api-conformance.jar --base-url ...`.
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the runnable conformance suite jar with its dependencies."
    archiveFileName.set("dulcet-api-conformance.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    manifest {
        attributes("Main-Class" to "com.legitimateapps.dulcet.apiconformance.MainKt")
    }
    from(sourceSets.main.map { it.output })
    val archives = archiveAccess.archives
    from(runtimeJars.map { jars -> jars.filter { it.name.endsWith(".jar") }.map { archives.zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.test {
    useJUnitPlatform()
    // The self-tests start in-process mock servers; one fault per run, so they are cheap but many.
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = false
    }
}
