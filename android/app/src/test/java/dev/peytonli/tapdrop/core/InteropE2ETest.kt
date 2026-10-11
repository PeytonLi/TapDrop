package dev.peytonli.tapdrop.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropE2ETest {
    @Test
    fun exchangesTextUrlAndFiveMegabyteFilesWithDotnetClient() = runBlocking {
        val qr = checkNotNull(System.getProperty("tapdrop.interop.qr")) { "Interop QR was not provided." }
        val directory = File(checkNotNull(System.getProperty("tapdrop.interop.dir")))
        val laptopFile = File(checkNotNull(System.getProperty("tapdrop.interop.laptopFile")))
        val pair = PairInfo.parseQrPayload(qr).copy(deviceName = "TapDrop Android E2E")
        val received = Channel<ObservedItem>(Channel.UNLIMITED)
        val client = RelayClient(pair, directory)
        try {
            client.register()
            client.start("phone") { item ->
                received.send(ObservedItem(item.item, item.blobFile?.readBytes()))
            }
            withTimeout(TimeUnit.SECONDS.toMillis(45)) { client.connectionState.first { it.connected } }

            client.sendText("android-to-laptop-text", "interop")
            client.sendUrl("https://example.com/tapdrop-android-e2e", "interop")
            val phoneBytes = ByteArray(5 * 1024 * 1024) { (it * 31).toByte() }
            val phoneFile = File(directory, "android-to-laptop-5m.bin").apply { writeBytes(phoneBytes) }
            client.sendFile(phoneFile, "application/octet-stream", source = "interop")
            File(directory, "android-sent").writeText("ready")

            val laptopText = receive(received)
            assertEquals("text", laptopText.item.kind)
            assertEquals("laptop-to-android-text", laptopText.item.text)
            val laptopFileItem = receive(received)
            assertEquals("file", laptopFileItem.item.kind)
            assertEquals(laptopFile.length(), laptopFileItem.item.blob?.size)
            assertTrue("Laptop-to-phone file bytes differ", laptopFile.readBytes().contentEquals(laptopFileItem.bytes))
        } finally {
            client.close()
            received.close()
        }
    }

    private suspend fun receive(channel: Channel<ObservedItem>): ObservedItem =
        withTimeout(TimeUnit.MINUTES.toMillis(3)) { channel.receive() }

    private data class ObservedItem(val item: Item, val bytes: ByteArray?)
}
