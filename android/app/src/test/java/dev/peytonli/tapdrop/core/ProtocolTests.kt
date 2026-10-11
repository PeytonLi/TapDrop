package dev.peytonli.tapdrop.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTests {
    private val vectors by lazy {
        val vectorsPath = System.getProperty("tapdrop.vectors") ?: error("Protocol vector path was not configured.")
        ProtocolJson.json.parseToJsonElement(File(vectorsPath).readText()).jsonObject
    }

    @Test
    fun reproducesKeyAndTokenVectors() {
        val masterKey = TapDropCrypto.fromBase64Url(vectors.getValue("masterKey_b64u").jsonPrimitive.content)
        assertEquals(vectors.getValue("encKey_hex").jsonPrimitive.content, TapDropCrypto.deriveEncryptionKey(masterKey).hex())
        assertEquals(vectors.getValue("authKey_hex").jsonPrimitive.content, TapDropCrypto.deriveAuthenticationKey(masterKey).hex())
        val authToken = TapDropCrypto.authToken(masterKey)
        assertEquals(vectors.getValue("authToken").jsonPrimitive.content, authToken)
        assertEquals(vectors.getValue("tokenHash_hex").jsonPrimitive.content, TapDropCrypto.tokenHash(authToken))
    }

    @Test
    fun parsesEncodedQrAndBuildsVectorQrExactly() {
        val qr = vectors.getValue("qrPayload").jsonPrimitive.content
        val parsed = PairInfo.parseQrPayload(qr)
        assertEquals(qr, parsed.toQrPayload())
        assertEquals("https://tapdrop-relay.example.workers.dev", parsed.relayUrl)
        assertEquals("Spectre x360", parsed.deviceName)

        val encoded = PairInfo("https://relay.example/path?q=one&two", "ZGVmZ2hpamtsbW5vcHFycw", parsed.masterKey, "Laptop & desk")
            .toQrPayload()
        val decoded = PairInfo.parseQrPayload(encoded)
        assertEquals("https://relay.example/path?q=one&two", decoded.relayUrl)
        assertEquals("Laptop & desk", decoded.deviceName)
        assertThrows(IllegalArgumentException::class.java) { PairInfo.parseQrPayload("https://example.com") }
    }

    @Test
    fun decryptsAndReproducesMessageVectorAndRejectsWrongAad() {
        val vector = vectors.getValue("message").jsonObject
        val pair = PairInfo.parseQrPayload(vectors.getValue("qrPayload").jsonPrimitive.content)
        val id = vector.getValue("id").jsonPrimitive.content
        val role = vector.getValue("from").jsonPrimitive.content
        val envelope = ProtocolJson.json.decodeFromJsonElement<Envelope>(vector.getValue("envelope"))
        val item = TapDropCrypto.decryptItem(pair, role, id, envelope)
        assertEquals("url", item.kind)
        assertEquals("https://example.com/a?b=c", item.url)
        assertEquals("Galaxy S24", item.sender)

        val nonce = TapDropCrypto.fromBase64Url(vector.getValue("nonce_b64u").jsonPrimitive.content)
        val encrypted = TapDropCrypto.encryptItem(pair, role, id, item, nonce)
        assertEquals(vector.getValue("envelope").jsonObject.getValue("c").jsonPrimitive.content, encrypted.c)
        assertThrows( javax.crypto.AEADBadTagException::class.java) {
            TapDropCrypto.decryptItem(pair, "laptop", id, envelope)
        }
    }

    @Test
    fun decryptsAndReproducesBlobVectorAndRejectsWrongAad() {
        val vector = vectors.getValue("blob").jsonObject
        val pair = PairInfo.parseQrPayload(vectors.getValue("qrPayload").jsonPrimitive.content)
        val blobId = vector.getValue("id").jsonPrimitive.content
        val body = vector.getValue("body_hex").jsonPrimitive.content.hexToBytes()
        val plaintext = TapDropCrypto.decryptBlob(pair, blobId, body)
        assertEquals(vector.getValue("plaintext_hex").jsonPrimitive.content, plaintext.hex())
        assertEquals(vector.getValue("plaintext_sha256_hex").jsonPrimitive.content, TapDropCrypto.sha256Hex(plaintext))
        assertEquals(body.toList(), TapDropCrypto.encryptBlob(pair, blobId, plaintext, body.copyOfRange(0, 12)).toList())
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            TapDropCrypto.decryptBlob(pair, "$blobId-wrong", body)
        }
    }

    @Test
    fun streamingBlobCryptoMatchesTheProtocolVector() {
        val vector = vectors.getValue("blob").jsonObject
        val pair = PairInfo.parseQrPayload(vectors.getValue("qrPayload").jsonPrimitive.content)
        val blobId = vector.getValue("id").jsonPrimitive.content
        val plaintext = vector.getValue("plaintext_hex").jsonPrimitive.content.hexToBytes()
        val expectedBody = vector.getValue("body_hex").jsonPrimitive.content.hexToBytes()
        val nonce = expectedBody.copyOfRange(0, 12)
        val encrypted = ByteArrayOutputStream()
        val encryptedResult = TapDropCrypto.encryptBlobStream(
            pair,
            blobId,
            ByteArrayInputStream(plaintext),
            encrypted,
            nonce,
        )
        assertArrayEquals(expectedBody, encrypted.toByteArray())
        assertEquals(plaintext.size.toLong(), encryptedResult.plainSize)
        assertEquals(vector.getValue("plaintext_sha256_hex").jsonPrimitive.content, encryptedResult.plainSha256Hex)

        val decrypted = ByteArrayOutputStream()
        val decryptedResult = TapDropCrypto.decryptBlobStream(pair, blobId, ByteArrayInputStream(expectedBody), decrypted)
        assertArrayEquals(plaintext, decrypted.toByteArray())
        assertEquals(plaintext.size.toLong(), decryptedResult.plainSize)
        assertEquals(vector.getValue("plaintext_sha256_hex").jsonPrimitive.content, decryptedResult.plainSha256Hex)
    }

    @Test
    fun streamingBlobCryptoMatchesOneShotAcrossBlockBoundaries() {
        val pair = PairInfo.parseQrPayload(vectors.getValue("qrPayload").jsonPrimitive.content)
        val blobId = "streaming-boundary-test"
        val nonce = ByteArray(12) { it.toByte() }
        val sizes = listOf(0, 1, 15, 16, 17, 65535, 65536, 65537, 1024 * 1024 + 3)
        for (size in sizes) {
            val plaintext = ByteArray(size) { ((it * 31 + size) % 256).toByte() }
            val expectedBody = TapDropCrypto.encryptBlob(pair, blobId, plaintext, nonce)
            val encrypted = ByteArrayOutputStream()
            val encryptedResult = TapDropCrypto.encryptBlobStream(
                pair,
                blobId,
                ByteArrayInputStream(plaintext),
                encrypted,
                nonce,
            )
            assertArrayEquals("Encryption mismatch at $size bytes", expectedBody, encrypted.toByteArray())
            assertEquals(size.toLong(), encryptedResult.plainSize)
            assertEquals(TapDropCrypto.sha256Hex(plaintext), encryptedResult.plainSha256Hex)

            val decrypted = ByteArrayOutputStream()
            val decryptedResult = TapDropCrypto.decryptBlobStream(
                pair,
                blobId,
                ByteArrayInputStream(expectedBody),
                decrypted,
            )
            assertArrayEquals("Decryption mismatch at $size bytes", plaintext, decrypted.toByteArray())
            assertEquals(size.toLong(), decryptedResult.plainSize)
            assertEquals(TapDropCrypto.sha256Hex(plaintext), decryptedResult.plainSha256Hex)
        }
    }

    @Test
    fun streamingBlobCryptoRejectsTamperedTagAndWrongAad() {
        val pair = PairInfo.parseQrPayload(vectors.getValue("qrPayload").jsonPrimitive.content)
        val blobId = "streaming-authentication-test"
        val nonce = ByteArray(12) { it.toByte() }
        val encrypted = TapDropCrypto.encryptBlob(pair, blobId, "authenticated".toByteArray(), nonce)
        val tampered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(InvalidCipherTextException::class.java) {
            TapDropCrypto.decryptBlobStream(pair, blobId, ByteArrayInputStream(tampered), ByteArrayOutputStream())
        }
        assertThrows(InvalidCipherTextException::class.java) {
            TapDropCrypto.decryptBlobStream(pair, "$blobId-wrong", ByteArrayInputStream(encrypted), ByteArrayOutputStream())
        }
    }

    @Test
    fun extractsDirectAndTitlePrefixedUrls() {
        assertEquals(SharedText("url", null, "https://example.com/a"), ShareText.parse("  https://example.com/a \n"))
        assertEquals(SharedText("url", null, "https://youtube.com/watch?v=1"), ShareText.parse("Video title\nhttps://youtube.com/watch?v=1"))
        assertEquals(SharedText("text", "hello\nhttps://example.com\nand goodbye", null), ShareText.parse("hello\nhttps://example.com\nand goodbye"))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
