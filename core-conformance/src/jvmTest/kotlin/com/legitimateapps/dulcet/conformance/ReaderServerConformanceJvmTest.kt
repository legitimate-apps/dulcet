package com.legitimateapps.dulcet.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The JVM leg of [ReaderServerConformanceTest]: the same server facts, read through Ktor CIO. */
class ReaderServerConformanceJvmTest : ReaderServerConformanceTest() {
    override fun moveDirectory(source: String, destination: String) {
        // A rename, never a copy: the server's watcher must see the album leave in one step.
        Files.move(path(source), path(destination), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    override fun isDirectory(path: String): Boolean = Files.isDirectory(path(path))

    override fun isFile(path: String): Boolean = Files.isRegularFile(path(path))

    override fun createDirectories(path: String) {
        Files.createDirectories(path(path))
    }

    private fun path(value: String): Path = Paths.get(value)
}
