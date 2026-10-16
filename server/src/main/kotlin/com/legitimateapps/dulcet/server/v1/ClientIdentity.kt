package com.legitimateapps.dulcet.server.v1

import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class ClientIdentity(val id: String, val name: String, val verified: Boolean, val redirects: Set<String> = emptySet())

/** Resolve, validate and pin the actual connection; TLS still authenticates the original hostname. */
internal class ClientIdentities(private val preloaded: (String) -> JsonObject? = { null }) {
    private val cache = ConcurrentHashMap<String, Pair<Long, ClientIdentity>>()
    private val deadline = Timer("dulcet-client-metadata-deadline", true)
    fun get(id: String): ClientIdentity {
        val uri = runCatching { URI(id) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.fragment != null || id.length > 2048) throw OAuthFailure("invalid_client")
        preloaded(id)?.let { return fromDocument(id,it) }
        cache[id]?.takeIf { it.first > System.currentTimeMillis() }?.let { return it.second }
        val identity = runCatching {
            fromDocument(id,Json.parseToJsonElement(fetch(uri)).jsonObject)
        }.getOrElse { ClientIdentity(id, id, false) }
        if (cache.size >= 2048) cache.entries.removeIf { it.value.first <= System.currentTimeMillis() }
        if (cache.size < 2048) cache[id] = System.currentTimeMillis() + (if (identity.verified) 86_400_000L else 60_000L) to identity
        return identity
    }
    fun validateRedirect(client: ClientIdentity, value: String) {
        val uri = runCatching { URI(value) }.getOrNull() ?: throw OAuthFailure("invalid_request")
        if (uri.userInfo != null || uri.fragment != null || value.length > 2048) throw OAuthFailure("invalid_request")
        val loopback = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]", "::1")
        val privateScheme = uri.scheme?.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*){2,}")) == true && uri.rawAuthority == null
        if (uri.scheme != "https" && !loopback && !privateScheme) throw OAuthFailure("invalid_request")
        if (client.verified) {
            val matches = client.redirects.any { registered ->
                if (registered == value) true else if (loopback) runCatching {
                    val r = URI(registered)
                    r.scheme == uri.scheme && r.host == uri.host && r.rawPath == uri.rawPath && r.rawQuery == uri.rawQuery && r.fragment == null
                }.getOrDefault(false) else false
            }
            if (!matches) throw OAuthFailure("invalid_request")
        } else if (!loopback && !privateScheme) throw OAuthFailure("invalid_request")
    }
    companion object {
        internal fun fromDocument(id:String,document:JsonObject):ClientIdentity {
            val uri=URI(id)
            require(uri.scheme=="https" && uri.host!=null && uri.rawUserInfo==null && uri.rawFragment==null && id.length<=2048)
            require(document["client_id"]==JsonPrimitive(id))
            val name=(document["client_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid client name")
            val redirects=(document["redirect_uris"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Invalid redirect") }?.toSet() ?: error("Missing redirects")
            require(name.isNotBlank() && redirects.isNotEmpty())
            redirects.forEach { redirect ->
                val target=URI(redirect)
                val loopback=target.scheme=="http" && target.host in setOf("127.0.0.1","[::1]","::1")
                val privateScheme=target.scheme?.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*){2,}"))==true && target.rawAuthority==null
                require(target.rawUserInfo==null && target.rawFragment==null && redirect.length<=2048 && (target.scheme=="https" && target.host!=null || loopback || privateScheme))
            }
            return ClientIdentity(id,name.take(100),true,redirects)
        }
    }
    private fun fetch(uri: URI): String {
        Socket().use { socket ->
            val closeTask=object : TimerTask() { override fun run() { runCatching { socket.close() } } }
            deadline.schedule(closeTask,5000)
            try {
            val addresses = CompletableFuture.supplyAsync { InetAddress.getAllByName(uri.host) }.get(5,TimeUnit.SECONDS)
            require(addresses.isNotEmpty() && addresses.all(::publicAddress))
            val address = addresses.first()
            val port = if (uri.port < 0) 443 else uri.port
            socket.connect(InetSocketAddress(address, port), 5000)
            socket.soTimeout = 5000
            require(publicAddress(socket.inetAddress))
            val tls = SSLSocketFactory.getDefault().let { it as SSLSocketFactory }.createSocket(socket, uri.host, port, true) as SSLSocket
            tls.use {
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                val path = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                require(!path.contains('\r') && !path.contains('\n'))
                tls.outputStream.write("GET $path HTTP/1.1\r\nHost: ${uri.host}${if (port == 443) "" else ":$port"}\r\nAccept: application/json\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                val response = tls.inputStream.readNBytes(80 * 1024 + 1)
                require(response.size <= 80 * 1024)
                val text = response.toString(StandardCharsets.UTF_8)
                val headerEnd = text.indexOf("\r\n\r\n")
                require(headerEnd in 0..16_384)
                val header = text.substring(0, headerEnd)
                require(header.lineSequence().first().matches(Regex("HTTP/1\\.[01] 200(?: .*)?")))
                var body = text.substring(headerEnd + 4)
                if (header.lowercase().contains("transfer-encoding: chunked")) {
                    val decoded = StringBuilder()
                    while (true) {
                        val end = body.indexOf("\r\n")
                        require(end in 1..100)
                        val length = body.substring(0, end).substringBefore(';').toInt(16)
                        body = body.substring(end + 2)
                        if (length == 0) break
                        require(length > 0 && length <= body.length && decoded.length + length <= 65_536)
                        decoded.append(body.substring(0, length))
                        require(body.substring(length).startsWith("\r\n"))
                        body = body.substring(length + 2)
                    }
                    body = decoded.toString()
                }
                require(body.toByteArray().size <= 65_536)
                return body
            }
            } finally { closeTask.cancel(); deadline.purge() }
        }
    }
    private fun publicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 255 }
        return if (b.size == 4) b[0] !in setOf(0, 127, 169) && !(b[0] == 100 && b[1] in 64..127) && b[0] < 224
        else b[0] and 0xfe != 0xfc && !(b.take(12).all { it == 0 } || b.take(10).all { it == 0 } && b[10] == 255 && b[11] == 255)
    }
}
