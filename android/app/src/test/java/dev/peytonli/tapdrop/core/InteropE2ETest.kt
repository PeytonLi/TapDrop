package dev.peytonli.tapdrop.core

import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class InteropE2ETest {
    @Test
    fun exchangesTextUrlAndNinetyMegabyteFilesWithDotnetClient() = runBlocking {
        val qr = checkNotNull(System.getProperty("tapdrop.interop.qr")) { "Interop QR was not provided." }
        val directory = File(checkNotNull(System.getProperty("tapdrop.interop.dir")))
        val phoneFile = File(checkNotNull(System.getProperty("tapdrop.interop.phoneFile")))
        val laptopFile = File(checkNotNull(System.getProperty("tapdrop.interop.laptopFile")))
        val pair = PairInfo.parseQrPayload(qr).copy(deviceName = "TapDrop Android E2E")
        val received = Channel<ObservedItem>(Channel.UNLIMITED)
        val client = RelayClient(pair, directory)
        try {
            client.register()
            client.start("phone") { item ->
                received.send(ObservedItem(item.item, item.blobFile?.let(::sha256File)))
            }
            withTimeout(45.seconds) { client.connectionState.first { it.connected } }

            client.sendText("android-to-laptop-text", "interop")
            client.sendUrl("https://example.com/tapdrop-android-e2e", "interop")
            client.sendFile(phoneFile, "application/octet-stream", source = "interop")
            File(directory, "android-sent").writeText("ready")

            val laptopText = receive(received)
            assertEquals("text", laptopText.item.kind)
            assertEquals("laptop-to-android-text", laptopText.item.text)
            val laptopFileItem = receive(received)
            assertEquals("file", laptopFileItem.item.kind)
            assertEquals(laptopFile.length(), laptopFileItem.item.blob?.size)
            assertEquals(sha256File(laptopFile), laptopFileItem.blobSha256)
        } finally {
            client.close()
            received.close()
        }
    }

    private suspend fun receive(channel: Channel<ObservedItem>): ObservedItem =
        withTimeout(3.minutes) { channel.receive() }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ObservedItem(val item: Item, val blobSha256: String?)
}
