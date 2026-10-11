package dev.peytonli.tapdrop.core

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobUploadRetryTests {
    @Test
    fun retriesTransientServerFailureThenSucceeds() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
        val client = RelayClient(pair(server), File(System.getProperty("java.io.tmpdir")), httpClient = OkHttpClient())
        try {
            val blob = client.uploadBlob("payload".toByteArray(), "test.txt", "text/plain")

            assertEquals(7, blob.size)
            assertEquals(2, server.requestCount)
            assertEquals("PUT", server.takeRequest().method)
            assertEquals("PUT", server.takeRequest().method)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun doesNotRetryPermanentClientFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
        val client = RelayClient(pair(server), File(System.getProperty("java.io.tmpdir")), httpClient = OkHttpClient())
        try {
            val error = runCatching {
                client.uploadBlob("payload".toByteArray(), "test.txt", "text/plain")
            }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    private fun pair(server: MockWebServer) = PairInfo(
        server.url("/").toString().trimEnd('/'),
        "abcdefghijklmnopqrstuv",
        TapDropCrypto.toBase64Url(ByteArray(32)),
        "Test",
    )
}
