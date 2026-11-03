package com.legitimateapps.dulcet.core

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath

/** A resource belongs to the request's route, including its segment-bounded base path.
 * This does not authorize another route: even an enrolled alias needs a new request/plan.
 * Null is deliberately content-free; resource queries may contain grants or REST credentials.
 */
public object ConnectionResourcePolicy {
    public fun resolve(baseUrl: String, resource: String): String? = try {
        resolveChecked(baseUrl, resource)
    } catch (_: Exception) { null }

    private fun resolveChecked(baseUrl: String, resource: String): String? {
        if (resource.isBlank() || resource != resource.trim() ||
            resource.any { it.isISOControl() || it == '\\' } || resource.startsWith("//") ||
            '#' in resource || unsafePath(resource.substringBefore('?'))
        ) return null
        val base = Url(baseUrl)
        if (base.protocol.name !in setOf("https", "http") || base.user != null || base.password != null ||
            base.parameters.names().isNotEmpty() || base.fragment.isNotEmpty() || unsafePath(base.encodedPath)
        ) return null
        val directory = base.toString().trimEnd('/') + "/"
        // Resolve against a directory, never against the last component as a filename.
        val absolute = when {
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(resource) -> resource
            resource.startsWith('/') -> "${base.protocol.name}://${base.host.let { if (':' in it) "[$it]" else it }}:${base.port}$resource"
            resource.startsWith('?') -> directory + resource
            else -> directory + resource
        }
        val target = Url(absolute)
        if (target.user != null || target.password != null || target.fragment.isNotEmpty() ||
            target.protocol != base.protocol || target.host.lowercase() != base.host.lowercase() || target.port != base.port ||
            unsafePath(target.encodedPath)
        ) return null
        val root = base.encodedPath.trimEnd('/')
        val path = target.encodedPath
        if (root.isNotEmpty() && path != root && !path.startsWith("$root/")) return null
        return URLBuilder(target).apply { host = target.host.lowercase() }.buildString()
    }

    private fun unsafePath(value: String): Boolean {
        // Reject ambiguous separators and nested escaping before any engine can reinterpret them.
        val path = if ("://" in value) value.substringAfter("://").substringAfter('/', "") else value
        if (Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return true
        return path.split('/').any { segment ->
            val dots = segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".")
            dots == "." || dots == ".."
        }
    }
}
