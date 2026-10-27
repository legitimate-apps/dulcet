package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/** Raw framing controls for the media-only Darwin session, over a real loopback socket. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class StrictMediaFramingTest {
    @Test fun multipleChunksExtensionsAndTrailersComplete() = response(
        body = "2;name=extension\r\nab\r\n3\r\ncde\r\n0\r\nX-Finished: yes\r\n\r\n",
        expected = "abcde",
    )
    @Test fun anIncompleteTrailerDoesNotComplete() = response(body = "2\r\nab\r\n0\r\n", expected = null)
    @Test fun ambiguousFramingDoesNotComplete() = response(
        body = "2\r\nab\r\n0\r\n\r\n", expected = null, extraHeaders = "Content-Length: 2\r\n",
    )
    @Test fun earlyHintsDoNotReplaceTheMediaResponse() = response(
        body = "2\r\nab\r\n0\r\n\r\n", expected = "ab",
        prefix = "HTTP/1.1 103 Early Hints\r\nLink: </art>; rel=preload\r\n\r\n",
    )
    @Test fun aDownloadBoundaryCutDoesNotComplete() = response(body = "2\r\nab\r\n", expected = null, endpoint = "download")
    @Test fun aTranscodeBoundaryCutDoesNotComplete() = response(body = "2\r\nab\r\n", expected = null, endpoint = "getTranscodeStream")

    private fun response(body: String, expected: String?, extraHeaders: String = "", prefix: String = "", endpoint: String = "stream"): Unit = runBlocking {
        val server = OneShotLoopbackServer(
            (prefix + "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n" + extraHeaders +
                "Content-Type: audio/mpeg\r\nConnection: close\r\n\r\n" + body).encodeToByteArray(),
        )
        val dispatcher = newSingleThreadContext("strict-media-framing-fixture")
        val client = AuthenticatedEndpointClient(
            AuthenticatedEndpointCredentials("http://127.0.0.1:${server.port}", "fixture-user", "fixture-password", true),
            "strict media test",
        )
        try {
            val served = CoroutineScope(dispatcher).async { server.serveOnce() }
            val result = withTimeout(15_000) { runCatching { client.request(endpoint, emptyMap()) } }
            withTimeout(15_000) { served.await() }
            if (expected == null) assertNotNull(result.exceptionOrNull(), "an incomplete framed response succeeded")
            else assertContentEquals(expected.encodeToByteArray(), result.getOrThrow().body)
        } finally {
            client.close()
            server.closeListener()
            dispatcher.close()
        }
    }
}
