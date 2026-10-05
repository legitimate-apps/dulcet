package com.legitimateapps.dulcet.core

import platform.Foundation.NSLog

/**
 * The reader facade's own failures, in the unified log: which step failed and the failure's class,
 * never its message, which can carry the server address or the credentials in a query string. A
 * screen states such a failure only as "something went wrong on this device"; this line is how a
 * run that showed one says which step it was.
 */
internal object AppleReaderDiagnostics {
    fun failed(step: String, failure: Throwable? = null) {
        val kind = failure?.let { it::class.qualifiedName ?: it::class.simpleName } ?: "no session"
        // The whole line is the format, with `%` escaped: NSLog's C varargs take no Kotlin string.
        NSLog("Dulcet reader: $step failed ($kind)".replace("%", "%%"))
    }
}
