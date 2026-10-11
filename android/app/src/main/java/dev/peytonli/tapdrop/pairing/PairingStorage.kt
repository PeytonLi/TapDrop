package dev.peytonli.tapdrop.pairing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.peytonli.tapdrop.core.PairInfo
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class PairingRecord(val pair: PairInfo, val laptopName: String)

class PairingStorage(context: Context) {
    private val preferences = context.getSharedPreferences("tapdrop_pair", Context.MODE_PRIVATE)

    fun load(): PairingRecord? {
        val relayUrl = preferences.getString(KEY_RELAY, null) ?: return null
        val pairId = preferences.getString(KEY_PAIR_ID, null) ?: return null
        val encrypted = preferences.getString(KEY_KEY, null) ?: return null
        val laptopName = preferences.getString(KEY_LAPTOP_NAME, "Laptop") ?: "Laptop"
        val deviceName = preferences.getString(KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL
        return try {
            val bytes = Base64.getDecoder().decode(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val masterKey = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            PairingRecord(
                PairInfo(relayUrl, pairId, dev.peytonli.tapdrop.core.TapDropCrypto.toBase64Url(masterKey), deviceName),
                laptopName,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(pair: PairInfo, laptopName: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(pair.masterKeyBytes)
        preferences.edit()
            .putString(KEY_RELAY, pair.relayUrl)
            .putString(KEY_PAIR_ID, pair.pairId)
            .putString(KEY_KEY, Base64.getEncoder().encodeToString(encrypted))
            .putString(KEY_LAPTOP_NAME, laptopName)
            .putString(KEY_DEVICE_NAME, pair.deviceName)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "tapdrop_pair"
        private const val KEY_RELAY = "relayUrl"
        private const val KEY_PAIR_ID = "pairId"
        private const val KEY_KEY = "masterKey"
        private const val KEY_LAPTOP_NAME = "laptopName"
        private const val KEY_DEVICE_NAME = "deviceName"
    }
}

@Serializable
data class RecentEntry(
    val kind: String,
    val text: String? = null,
    val uri: String? = null,
    val sender: String,
    val createdAt: Long,
)

class RecentStore(context: Context) {
    private val file = File(context.filesDir, "recent.json")
    private val lock = Any()

    fun load(): List<RecentEntry> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        runCatching { json.decodeFromString(ListSerializer(RecentEntry.serializer()), file.readText()) }
            .getOrDefault(emptyList())
    }

    fun add(entry: RecentEntry): List<RecentEntry> = synchronized(lock) {
        val items = (listOf(entry) + load()).take(20)
        file.writeText(json.encodeToString(ListSerializer(RecentEntry.serializer()), items))
        items
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
