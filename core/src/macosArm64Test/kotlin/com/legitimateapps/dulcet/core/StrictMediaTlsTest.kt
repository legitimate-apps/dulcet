package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.*
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A disposable loopback TLS server must not make its self-signed certificate trusted. */
@OptIn(ExperimentalForeignApi::class)
class StrictMediaTlsTest {
    @Test fun anUntrustedLoopbackCertificateFailsClosed(): Unit = runBlocking {
        val directory = NSTemporaryDirectory() + "dulcet-media-tls-" + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, true, null, null))
        val port = OneShotLoopbackServer(byteArrayOf()).let { it.closeListener(); it.port }
        var server: NSTask? = null
        val client = AuthenticatedEndpointClient(
            AuthenticatedEndpointCredentials("https://127.0.0.1:$port", "tls-user-canary", "tls-password-canary", false),
            "strict media TLS test",
        )
        try {
            val certificate = process(listOf("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-subj", "/CN=localhost", "-keyout", "$directory/key.pem", "-out", "$directory/cert.pem"))
            certificate.waitUntilExit()
            assertEquals(0, certificate.terminationStatus, "TLS fixture certificate generation failed")
            val readyPath = "$directory/server.log"
            check(files.createFileAtPath(readyPath, null, null))
            val readyOutput = requireNotNull(NSFileHandle.fileHandleForWritingAtPath(readyPath))
            server = process(listOf("-c", TLS_FIXTURE, "$directory/cert.pem", "$directory/key.pem", port.toString()),
                readyOutput, "/usr/bin/python3")
            val started = TimeSource.Monotonic.markNow()
            while (NSString.create(contentsOfFile = readyPath, encoding = NSUTF8StringEncoding, error = null)
                    ?.toString()?.contains("ACCEPT") != true) {
                assertTrue(server.running, "TLS fixture did not start")
                check(started.elapsedNow().inWholeSeconds < 10) { "TLS fixture readiness timed out" }
                NSThread.sleepForTimeInterval(0.01)
            }
            readyOutput.closeFile()
            val failure = withTimeout(10_000) { runCatching { client.request("stream", emptyMap()) }.exceptionOrNull() }
            requireNotNull(failure)
            assertIs<DomainError.Security.TlsUntrusted>(mapAccountConnectionFailure(failure))
            Unit
        } finally {
            client.close()
            if (server?.running == true) server.terminate()
            server?.waitUntilExit()
            files.removeItemAtPath(directory, null)
        }
    }

    private val TLS_FIXTURE = """
        import socket, ssl, sys
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(sys.argv[1], sys.argv[2])
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", int(sys.argv[3])))
            listener.listen(1)
            print("ACCEPT", flush=True)
            connection, _ = listener.accept()
            try:
                with context.wrap_socket(connection, server_side=True) as secure:
                    secure.recv(4096)
                    secure.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nab")
            except ssl.SSLError:
                connection.close()
    """.trimIndent()

    private fun process(arguments: List<String>, output: Any = NSFileHandle.fileHandleWithNullDevice, program: String = "/usr/bin/openssl") = NSTask().apply {
        executableURL = NSURL.fileURLWithPath(program)
        this.arguments = arguments
        standardOutput = output
        standardError = NSFileHandle.fileHandleWithNullDevice
        check(launchAndReturnError(null))
    }
}
