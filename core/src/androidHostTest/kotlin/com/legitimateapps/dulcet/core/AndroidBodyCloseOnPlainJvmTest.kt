package com.legitimateapps.dulcet.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * No Robolectric on purpose: this JVM's android.jar throws from every method, as it does for the plain host
 * conformance suites, and a close must still release the body.
 */
class AndroidBodyCloseOnPlainJvmTest {
    @Test fun closingABodyOnAJvmWithoutALooperReleasesIt() {
        val closes = AtomicInteger()
        val tracked = object : ForwardingSource(Buffer().writeUtf8("body")) {
            override fun close() {
                closes.incrementAndGet()
                super.close()
            }
        }.buffer()
        val response = Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/rest/ping").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = 4
                override fun source(): BufferedSource = tracked
            }).build()

        val wrapped = response.withBodyClosedOffMain()
        assertEquals("body", wrapped.body.source().readUtf8())
        wrapped.close()

        assertEquals(1, closes.get(), "the wrapped close reaches the real body, on this thread")
    }
}
