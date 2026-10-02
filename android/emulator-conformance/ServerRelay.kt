package com.legitimateapps.dulcet.emulator

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A TCP relay the test process owns, in front of the disposable server. The app is given the
 * relay's loopback address as its server, so what the app can reach is what the relay forwards.
 *
 * [cut] makes the server unreachable to the app and to nothing else. This works whatever the
 * emulator's radios do: the runner reaches the server over loopback or a host alias, which
 * airplane mode does not remove. After the cut the relay still accepts connections, and closes
 * each one at once without forwarding it. It counts them, so a test can show both that the app's
 * path is closed and how often the app tried it.
 *
 * Every forwarded connection's bytes are also kept, up to [TAP_LIMIT] in each direction, so a test
 * can read what the server actually answered ([connections]). They stay in this process's memory
 * and carry credentials in their request lines: a test reads them and never prints them whole.
 */
class ServerRelay(target: String) : AutoCloseable {
    private val targetUri = URI(target)
    private val targetHost = targetUri.host
    private val targetPort = if (targetUri.port > 0) targetUri.port else 80
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val lock = Any()
    private val open = mutableSetOf<Socket>()
    private var isCut = false

    /** The server URL the app is given. */
    val url: String = "http://127.0.0.1:${listener.localPort}"

    /** Connections forwarded to the server, and bytes carried in either direction. */
    val forwardedConnections = AtomicInteger()
    val forwardedBytes = AtomicLong()

    /** Connections accepted after [cut], each closed at once and never forwarded. */
    val refusedConnections = AtomicInteger()

    /** One forwarded connection's bytes: what the app sent, and what the server answered. */
    class Tapped internal constructor() {
        internal val sent = java.io.ByteArrayOutputStream()
        internal val answered = java.io.ByteArrayOutputStream()
        fun sent(): ByteArray = synchronized(this) { sent.toByteArray() }
        fun answered(): ByteArray = synchronized(this) { answered.toByteArray() }
    }

    private val tapped = mutableListOf<Tapped>()

    /** Every connection forwarded, in the order accepted. */
    fun connections(): List<Tapped> = synchronized(lock) { tapped.toList() }

    init {
        thread(name = "server-relay-accept", isDaemon = true) {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                val upstream = synchronized(lock) {
                    if (isCut) null else try {
                        Socket(targetHost, targetPort).also { open += client; open += it }
                    } catch (_: IOException) { null }
                }
                if (upstream == null) {
                    if (synchronized(lock) { isCut }) refusedConnections.incrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                forwardedConnections.incrementAndGet()
                val tap = Tapped().also { synchronized(lock) { tapped += it } }
                val finished = AtomicInteger()
                val release = {
                    if (finished.incrementAndGet() == 2) {
                        runCatching { client.close() }; runCatching { upstream.close() }
                        synchronized(lock) { open -= client; open -= upstream }
                    }
                }
                pump(client, upstream, release) { bytes, count -> keep(tap, tap.sent, bytes, count) }
                pump(upstream, client, release) { bytes, count -> keep(tap, tap.answered, bytes, count) }
            }
        }
    }

    /** Closes every forwarded connection and forwards nothing from now on. */
    fun cut() {
        val closing = synchronized(lock) { isCut = true; open.toList().also { open.clear() } }
        closing.forEach { runCatching { it.close() } }
    }

    override fun close() {
        cut()
        runCatching { listener.close() }
    }

    private fun keep(tap: Tapped, sink: java.io.ByteArrayOutputStream, bytes: ByteArray, count: Int) = synchronized(tap) {
        val room = TAP_LIMIT - sink.size()
        if (room > 0) sink.write(bytes, 0, minOf(room, count))
    }

    private fun pump(from: Socket, to: Socket, release: () -> Unit, tap: (ByteArray, Int) -> Unit) = thread(name = "server-relay-pump", isDaemon = true) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                // Checked per chunk, so a chunk read across the cut is never delivered.
                if (synchronized(lock) { isCut }) break
                tap(buffer, count)
                output.write(buffer, 0, count)
                output.flush()
                forwardedBytes.addAndGet(count.toLong())
            }
        } catch (_: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
            if (synchronized(lock) { isCut }) { runCatching { from.close() }; runCatching { to.close() } }
            release()
        }
    }

    private companion object {
        /** Bytes kept per direction per connection: the head of every answer, not whole songs. */
        const val TAP_LIMIT = 4 * 1024 * 1024
    }
}
