package com.legitimateapps.dulcet.emulator

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
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
 * each one without forwarding it. It counts them immediately, then reads only the first request
 * line for diagnostics (bounded by one second), so a test can show both that the app's
 * path is closed and how often the app tried it.
 *
 * [makeUnreachable] is [cut] undone by [makeReachable]: the server is unreachable to the app until
 * then, and refused connections are counted the same way. [hold] parks every connection accepted
 * from then on, neither forwarded nor closed, until [release] forwards them: the app's request is
 * in flight for as long as a test needs to look at what the app shows meanwhile.
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
    private val diagnosticSockets = mutableSetOf<Socket>()
    private var isCut = false

    /** The server URL the app is given. */
    val url: String = "http://127.0.0.1:${listener.localPort}"

    /** Every app connection accepted, before any upstream dial, hold, or diagnostic read. */
    val acceptedConnections = AtomicInteger()

    /** Connections forwarded to the server, and bytes carried in either direction. */
    val forwardedConnections = AtomicInteger()
    val forwardedBytes = AtomicLong()

    /** Connections accepted after [cut], counted before the bounded diagnostic read and never forwarded. */
    val refusedConnections = AtomicInteger()

    /** One forwarded connection's bytes: what the app sent, and what the server answered. */
    class Tapped internal constructor(internal val acceptedNanos: Long) {
        internal val sent = java.io.ByteArrayOutputStream()
        internal val answered = java.io.ByteArrayOutputStream()
        fun sent(): ByteArray = synchronized(this) { sent.toByteArray() }
        fun answered(): ByteArray = synchronized(this) { answered.toByteArray() }
    }

    private val tapped = mutableListOf<Tapped>()

    /** Safe diagnostics only: no host, headers, query, or raw request line is exposed here. */
    private class Contact(val acceptedNanos: Long) {
        private var disposition = "accepted"
        private var countedNanos: Long? = null
        private var requestNanos: Long? = null
        private var requestLine = "<no request line observed>"
        private val firstLine = java.io.ByteArrayOutputStream()
        private var finished = false

        @Synchronized fun record(bytes: ByteArray, count: Int) {
            if (finished) return
            for (index in 0 until count) {
                val byte = bytes[index].toInt()
                if (byte == 10) {
                    val parts = firstLine.toString("US-ASCII").trimEnd('\r').split(' ')
                    // URI rejects malformed targets. Only origin/absolute-form paths are emitted;
                    // method/version are validated, and the entire query is discarded.
                    val path = parts.getOrNull(1)?.let { target ->
                        runCatching { URI(target).rawPath }.getOrNull()
                    }?.takeIf { it.startsWith('/') && it.all { c -> c.code in 33..126 } }
                    requestLine = if (parts.size == 3 && parts[0].matches(Regex("[A-Z]+")) &&
                        parts[2].matches(Regex("HTTP/[0-9]+\\.[0-9]+")) && path != null
                    ) "${parts[0]} $path ${parts[2]}" else "<unrecognized request line>"
                    requestNanos = System.nanoTime()
                    finished = true
                    firstLine.reset()
                    return
                }
                if (firstLine.size() == 8192) {
                    requestLine = "<request line exceeds diagnostic limit>"
                    finished = true
                    firstLine.reset()
                    return
                }
                firstLine.write(byte)
            }
        }

        @Synchronized fun counted(asDisposition: String) {
            disposition = asDisposition
            countedNanos = System.nanoTime()
        }

        @Synchronized fun describe(relativeToNanos: Long): String {
            fun relative(time: Long?) = time?.let { "${(it - relativeToNanos) / 1_000}us" } ?: "pending"
            return "accept=${relative(acceptedNanos)} count=${relative(countedNanos)} " +
                "first-request=${relative(requestNanos)} $disposition $requestLine"
        }
    }

    private val contacts = mutableListOf<Contact>()

    /** All accepts, including held/refused ones; negative times belong before the relaunch marker. */
    fun connectionDiagnostics(relativeToNanos: Long): String = synchronized(lock) { contacts.toList() }
        .mapIndexed { index, contact -> "#${index + 1} ${contact.describe(relativeToNanos)}" }
        .joinToString("\n")

    /** Every connection forwarded, in the order accepted. */
    fun connections(): List<Tapped> = synchronized(lock) { tapped.sortedBy { it.acceptedNanos } }

    /** Connections accepted while held, and not yet released. */
    val heldConnections = AtomicInteger()
    private var isHeld = false
    private val parked = mutableListOf<Pair<Socket, Contact>>()

    init {
        thread(name = "server-relay-accept", isDaemon = true) {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                val contact = Contact(System.nanoTime()).also {
                    synchronized(lock) { contacts += it; acceptedConnections.incrementAndGet() }
                }
                val held = synchronized(lock) {
                    if (isHeld && !isCut) { parked += client to contact; heldConnections.incrementAndGet(); true } else false
                }
                // An upstream dial may block. Never let it leave later app connections queued at
                // the listener and counted in a different test phase from the one that made them.
                if (!held) thread(name = "server-relay-forward", isDaemon = true) { forward(client, contact) }
            }
        }
    }

    private fun forward(client: Socket, contact: Contact) {
        // Register before dialing so cut/close can interrupt an upstream connect in progress.
        // Dial outside the lock: the accept loop and cut must remain able to make progress.
        val candidate = Socket()
        val mayDial = synchronized(lock) {
            if (isCut || listener.isClosed) false else { open += client; open += candidate; true }
        }
        val connected = if (!mayDial) null else try {
            candidate.connect(InetSocketAddress(targetHost, targetPort))
            candidate
        } catch (_: IOException) { null }
        val upstream = synchronized(lock) {
            connected?.takeIf { !isCut && !listener.isClosed }.also {
                if (it == null) { open -= client; open -= candidate }
            }
        }
        if (upstream == null) {
            runCatching { candidate.close() }
            if (listener.isClosed) { runCatching { client.close() }; return }
            if (synchronized(lock) { isCut }) {
                refusedConnections.incrementAndGet()
                contact.counted("refused")
            } else contact.counted("upstream-unavailable")
            // Count before reading: a connection with no HTTP bytes still violates zero-contact.
            synchronized(lock) { diagnosticSockets += client }
            thread(name = "server-relay-refused-diagnostic", isDaemon = true) {
                try {
                    client.soTimeout = 1000
                    val input = client.getInputStream()
                    val buffer = ByteArray(8193)
                    // read() may return a partial line; the socket deadline bounds the whole read.
                    val deadline = System.nanoTime() + 1_000_000_000L
                    var total = 0
                    while (total < buffer.size && System.nanoTime() < deadline) {
                        client.soTimeout = maxOf(1, ((deadline - System.nanoTime()) / 1_000_000).toInt())
                        val count = input.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                        if ((0 until total).any { buffer[it] == 10.toByte() }) break
                    }
                    contact.record(buffer, total)
                } catch (_: IOException) {
                } finally {
                    runCatching { client.close() }
                    synchronized(lock) { diagnosticSockets -= client }
                }
            }
            return
        }
        forwardedConnections.incrementAndGet()
        contact.counted("forwarded")
        val tap = Tapped(contact.acceptedNanos).also { synchronized(lock) { tapped += it } }
        val finished = AtomicInteger()
        val release = {
            if (finished.incrementAndGet() == 2) {
                runCatching { client.close() }; runCatching { upstream.close() }
                synchronized(lock) { open -= client; open -= upstream }
            }
        }
        pump(client, upstream, release) { bytes, count ->
            contact.record(bytes, count)
            keep(tap, tap.sent, bytes, count)
        }
        pump(upstream, client, release) { bytes, count -> keep(tap, tap.answered, bytes, count) }
    }

    /** Parks every connection accepted from now on until [release]. */
    fun hold() = synchronized(lock) { isHeld = true }

    /** Stops holding and forwards every parked connection, in the order accepted. */
    fun release() {
        val waiting = synchronized(lock) { isHeld = false; parked.toList().also { parked.clear() } }
        waiting.forEach { (client, contact) -> forward(client, contact) }
    }

    /** As [cut], until [makeReachable]. */
    fun makeUnreachable() = cut()

    /** Forwards new connections again after [makeUnreachable]. */
    fun makeReachable() = synchronized(lock) { isCut = false }

    /** Closes every forwarded connection and forwards nothing from now on. */
    fun cut() {
        val closing = synchronized(lock) { isCut = true; open.toList().also { open.clear() } }
        closing.forEach { runCatching { it.close() } }
    }

    override fun close() {
        cut()
        synchronized(lock) { parked.toList().also { parked.clear() } }.forEach { (client, _) -> runCatching { client.close() } }
        runCatching { listener.close() }
        synchronized(lock) { diagnosticSockets.toList().also { diagnosticSockets.clear() } }
            .forEach { runCatching { it.close() } }
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
