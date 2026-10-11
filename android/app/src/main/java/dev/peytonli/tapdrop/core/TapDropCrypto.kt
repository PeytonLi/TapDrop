package dev.peytonli.tapdrop.core

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class BlobStreamResult(val plainSize: Long, val plainSha256Hex: String)

object TapDropCrypto {
    private val random = SecureRandom()
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()
    private val salt = "tapdrop-v1".toByteArray(StandardCharsets.US_ASCII)
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    fun deriveEncryptionKey(masterKey: ByteArray): ByteArray = deriveKey(masterKey, "enc")

    fun deriveAuthenticationKey(masterKey: ByteArray): ByteArray = deriveKey(masterKey, "auth")

    fun authToken(masterKey: ByteArray): String = toBase64Url(deriveAuthenticationKey(masterKey))

    fun tokenHash(authToken: String): String =
        sha256(authToken.toByteArray(StandardCharsets.US_ASCII)).toHex()

    fun encryptItem(pair: PairInfo, fromRole: String, id: String, item: Item, nonce: ByteArray? = null): Envelope {
        require(fromRole == "phone" || fromRole == "laptop")
        val iv = nonce ?: ByteArray(12).also(random::nextBytes)
        require(iv.size == 12)
        val plaintext = ProtocolJson.json.encodeToString(Item.serializer(), item).toByteArray(StandardCharsets.UTF_8)
        val encrypted = gcm(Cipher.ENCRYPT_MODE, pair.masterKeyBytes, iv, messageAad(pair, fromRole, id), plaintext)
        return Envelope(1, toBase64Url(iv), toBase64Url(encrypted))
    }

    fun decryptItem(pair: PairInfo, fromRole: String, id: String, envelope: Envelope): Item {
        require(envelope.v == 1) { "Unsupported encrypted message version." }
        val nonce = fromBase64Url(envelope.n)
        require(nonce.size == 12) { "Invalid message nonce." }
        val ciphertext = fromBase64Url(envelope.c)
        val plaintext = gcm(Cipher.DECRYPT_MODE, pair.masterKeyBytes, nonce, messageAad(pair, fromRole, id), ciphertext)
        return ProtocolJson.json.decodeFromString(Item.serializer(), plaintext.toString(StandardCharsets.UTF_8))
    }

    fun encryptBlob(pair: PairInfo, blobId: String, plaintext: ByteArray, nonce: ByteArray? = null): ByteArray {
        val iv = nonce ?: ByteArray(12).also(random::nextBytes)
        require(iv.size == 12)
        val encrypted = gcm(Cipher.ENCRYPT_MODE, pair.masterKeyBytes, iv, blobAad(pair, blobId), plaintext)
        return iv + encrypted
    }

    fun decryptBlob(pair: PairInfo, blobId: String, body: ByteArray): ByteArray {
        require(body.size >= 28) { "Encrypted blob is too short." }
        return gcm(Cipher.DECRYPT_MODE, pair.masterKeyBytes, body.copyOfRange(0, 12), blobAad(pair, blobId), body.copyOfRange(12, body.size))
    }

    fun encryptBlobStream(
        pair: PairInfo,
        blobId: String,
        input: InputStream,
        output: OutputStream,
        nonce: ByteArray = random12(),
    ): BlobStreamResult {
        require(nonce.size == 12)
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(
            true,
            AEADParameters(KeyParameter(deriveEncryptionKey(pair.masterKeyBytes)), 128, nonce, blobAad(pair, blobId)),
        )
        output.write(nonce)
        val digest = MessageDigest.getInstance("SHA-256")
        val inputBuffer = ByteArray(STREAM_BUFFER_SIZE)
        val outputBuffer = ByteArray(STREAM_BUFFER_SIZE + 32)
        var plainSize = 0L
        while (true) {
            val count = readChunk(input, inputBuffer)
            if (count < 0) break
            digest.update(inputBuffer, 0, count)
            plainSize += count
            val written = cipher.processBytes(inputBuffer, 0, count, outputBuffer, 0)
            if (written > 0) output.write(outputBuffer, 0, written)
        }
        val finalCount = cipher.doFinal(outputBuffer, 0)
        if (finalCount > 0) output.write(outputBuffer, 0, finalCount)
        return BlobStreamResult(plainSize, digest.digest().toHex())
    }

    fun decryptBlobStream(
        pair: PairInfo,
        blobId: String,
        input: InputStream,
        output: OutputStream,
    ): BlobStreamResult {
        val nonce = ByteArray(12)
        readFully(input, nonce)
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(
            false,
            AEADParameters(KeyParameter(deriveEncryptionKey(pair.masterKeyBytes)), 128, nonce, blobAad(pair, blobId)),
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val inputBuffer = ByteArray(STREAM_BUFFER_SIZE)
        val outputBuffer = ByteArray(STREAM_BUFFER_SIZE + 32)
        var encryptedSize = 0L
        var plainSize = 0L
        while (true) {
            val count = readChunk(input, inputBuffer)
            if (count < 0) break
            encryptedSize += count
            val written = cipher.processBytes(inputBuffer, 0, count, outputBuffer, 0)
            if (written > 0) {
                digest.update(outputBuffer, 0, written)
                plainSize += written
                output.write(outputBuffer, 0, written)
            }
        }
        if (encryptedSize < 16) throw InvalidCipherTextException("Encrypted blob is too short.")
        val finalCount = cipher.doFinal(outputBuffer, 0)
        if (finalCount > 0) {
            digest.update(outputBuffer, 0, finalCount)
            plainSize += finalCount
            output.write(outputBuffer, 0, finalCount)
        }
        return BlobStreamResult(plainSize, digest.digest().toHex())
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun toBase64Url(bytes: ByteArray): String = base64Encoder.encodeToString(bytes)

    fun fromBase64Url(value: String): ByteArray = base64Decoder.decode(value)

    private fun deriveKey(masterKey: ByteArray, info: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudorandomKey = extract.doFinal(masterKey)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
        return expand.doFinal(info.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(1)).copyOf(32)
    }

    private fun messageAad(pair: PairInfo, fromRole: String, id: String): ByteArray =
        "tapdrop/v1/msg/${pair.pairId}/$fromRole/$id".toByteArray(StandardCharsets.US_ASCII)

    private fun blobAad(pair: PairInfo, blobId: String): ByteArray =
        "tapdrop/v1/blob/${pair.pairId}/$blobId".toByteArray(StandardCharsets.US_ASCII)

    private fun gcm(mode: Int, masterKey: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(deriveEncryptionKey(masterKey), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        val count = input.read(buffer)
        if (count != 0) return count
        val value = input.read()
        if (value < 0) return -1
        buffer[0] = value.toByte()
        return 1
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw InvalidCipherTextException("Encrypted blob is too short.")
            if (count == 0) {
                val value = input.read()
                if (value < 0) throw InvalidCipherTextException("Encrypted blob is too short.")
                buffer[offset++] = value.toByte()
            } else {
                offset += count
            }
        }
    }

    private fun random12(): ByteArray = ByteArray(12).also(random::nextBytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
