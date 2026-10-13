package com.legitimateapps.dulcet.server

import java.nio.file.Files
import java.nio.file.Path

/** Resolve links and reject files outside the real music root, including stored traversal paths. */
fun musicFile(root: Path, file: Path): Path? = runCatching {
    val realRoot = root.toRealPath()
    file.toRealPath().takeIf { it.startsWith(realRoot) && Files.isRegularFile(it) }
}.getOrNull()
