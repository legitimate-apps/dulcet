package com.legitimateapps.dulcet.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EEXIST
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.strerror
import kotlinx.cinterop.toKString

/** The `macosArm64` leg of [ReaderServerConformanceTest]: the same server facts, read through NSURLSession. */
@OptIn(ExperimentalForeignApi::class)
class ReaderServerConformanceMacosTest : ReaderServerConformanceTest() {
    override fun moveDirectory(source: String, destination: String) {
        // rename(2): one atomic step, so the server's watcher sees the album leave at once.
        if (rename(source, destination) != 0) {
            error("rename failed: ${strerror(errno)?.toKString()}")
        }
    }

    override fun isDirectory(path: String): Boolean = mode(path)?.let { it and S_IFMT.toUInt() == S_IFDIR.toUInt() } ?: false

    override fun isFile(path: String): Boolean = mode(path)?.let { it and S_IFMT.toUInt() == S_IFREG.toUInt() } ?: false

    override fun createDirectories(path: String) {
        var current = if (path.startsWith('/')) "" else "."
        for (part in path.split('/').filter(String::isNotEmpty)) {
            current = "$current/$part"
            if (isDirectory(current)) continue
            if (mkdir(current, 0x1c0u) != 0 && errno != EEXIST) {
                error("mkdir failed: ${strerror(errno)?.toKString()}")
            }
        }
        check(isDirectory(path)) { "directory was not created" }
    }

    private fun mode(path: String): UInt? = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) null else info.st_mode.toUInt()
    }
}
