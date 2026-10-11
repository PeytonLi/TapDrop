package dev.peytonli.tapdrop.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

data class PairInfo(
    val relayUrl: String,
    val pairId: String,
    val masterKey: String,
    val deviceName: String,
) {
    val masterKeyBytes: ByteArray
        get() = TapDropCrypto.fromBase64Url(masterKey)

    val authToken: String
        get() = TapDropCrypto.toBase64Url(TapDropCrypto.deriveAuthenticationKey(masterKeyBytes))

    fun toQrPayload(): String {
        require(pairId.matches(PAIR_ID)) { "Invalid pair id." }
        require(masterKeyBytes.size == 32) { "Master key must be 32 bytes." }
        return "tapdrop://pair?v=1&r=${encode(relayUrl)}&p=$pairId&k=$masterKey&n=${encode(deviceName)}"
    }

    companion object {
        private val PAIR_ID = Regex("[A-Za-z0-9_-]{22}")
        private val RANDOM = SecureRandom()

        fun generate(relayUrl: String, deviceName: String): PairInfo {
            val id = ByteArray(16).also(RANDOM::nextBytes)
            val key = ByteArray(32).also(RANDOM::nextBytes)
            return PairInfo(relayUrl.trimEnd('/'), TapDropCrypto.toBase64Url(id), TapDropCrypto.toBase64Url(key), deviceName)
        }

        fun parseQrPayload(payload: String): PairInfo {
            val uri = try {
                URI(payload)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid TapDrop pairing QR.", error)
            }
            require(uri.scheme == "tapdrop" && uri.host == "pair") { "Not a TapDrop pairing QR." }
            val values = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) { "Malformed pairing QR parameter." }
                decode(entry.substring(0, separator)) to decode(entry.substring(separator + 1))
            }
            require(values["v"] == "1") { "Unsupported TapDrop protocol version." }
            val relayUrl = values["r"] ?: throw IllegalArgumentException("Pairing QR has no relay URL.")
            require(URI(relayUrl).scheme in setOf("http", "https")) { "Invalid relay URL." }
            val pairId = values["p"] ?: throw IllegalArgumentException("Pairing QR has no pair id.")
            require(pairId.matches(PAIR_ID)) { "Invalid pair id." }
            val masterKey = values["k"] ?: throw IllegalArgumentException("Pairing QR has no master key.")
            require(TapDropCrypto.fromBase64Url(masterKey).size == 32) { "Invalid master key." }
            val laptopName = values["n"] ?: throw IllegalArgumentException("Pairing QR has no device name.")
            return PairInfo(relayUrl.trimEnd('/'), pairId, masterKey, laptopName)
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
