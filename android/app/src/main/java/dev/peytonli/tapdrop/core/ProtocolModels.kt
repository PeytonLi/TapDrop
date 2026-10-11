package dev.peytonli.tapdrop.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Envelope(
    val v: Int,
    val n: String,
    val c: String,
)

@Serializable
data class BlobRef(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class Item(
    val kind: String,
    val text: String? = null,
    val url: String? = null,
    val blob: BlobRef? = null,
    val source: String = "share",
    @SerialName("createdAt") val createdAt: Long,
    val sender: String,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object ProtocolJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
