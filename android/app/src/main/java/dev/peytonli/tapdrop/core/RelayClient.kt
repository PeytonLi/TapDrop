package dev.peytonli.tapdrop.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl

data class RelayConnectionState(val connected: Boolean = false, val peerOnline: Boolean = false)

data class ReceivedItem(
    val messageId: String,
    val from: String,
    val item: Item,
    val blobFile: File?,
)

data class SendReceipt(val id: String, val connectedWhenQueued: Boolean)

class RelayClient(
    private val pair: PairInfo,
    private val cacheDirectory: File,
    httpClient: OkHttpClient? = null,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    private val http = httpClient ?: OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val outgoing = Channel<PendingSend>(Channel.UNLIMITED)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, PendingSend>()
    private val dedupeLock = Any()
    private val recentOrder = ArrayDeque<String>()
    private val recentIds = HashSet<String>()
    private val handlerFailures = LinkedHashMap<String, Int>(16, 0.75f, true)
    private val uploadRetrySignal = AtomicReference(UploadRetrySignal(0, CompletableDeferred()))
    private val socket = AtomicReference<WebSocket?>()
    private val reconnectRequests = Channel<Unit>(Channel.CONFLATED)
    private val lastPongNanos = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow(RelayConnectionState())
    private val _delivered = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private var runJob: Job? = null
    private var role: String? = null
    private var handler: (suspend (ReceivedItem) -> Unit)? = null

    val connectionState: StateFlow<RelayConnectionState> = _connectionState.asStateFlow()
    val delivered: SharedFlow<String> = _delivered.asSharedFlow()

    suspend fun register() {
        val request = Request.Builder()
            .url(pairUrl("/register"))
            .header("Authorization", "Bearer ${pair.authToken}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        execute(request, STANDARD_TIMEOUT).use { response ->
            if (response.code !in 200..299) throw RelayHttpException(response.code, "Pair registration failed")
        }
    }

    @Synchronized
    fun start(role: String, handler: suspend (ReceivedItem) -> Unit) {
        require(role == "laptop" || role == "phone") { "Role must be laptop or phone." }
        check(runJob == null) { "Relay client has already been started." }
        this.role = role
        this.handler = handler
        runJob = scope.launch { reconnectLoop(role) }
    }

    fun reconnectNow() {
        reconnectRequests.trySend(Unit)
        signalUploadRetries()
        socket.get()?.cancel()
    }

    suspend fun sendText(text: String, source: String = "share"): SendReceipt {
        checkStarted()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val item = if (bytes.size <= INLINE_TEXT_BYTES) {
            newItem(kind = "text", source = source, text = text)
        } else {
            newItem(kind = "text", source = source).copy(
                blob = uploadBlob(bytes, "text.txt", "text/plain"),
            )
        }
        return sendItem(item)
    }

    suspend fun sendUrl(url: String, source: String = "share"): SendReceipt {
        checkStarted()
        return sendItem(newItem(kind = "url", source = source, url = url))
    }

    suspend fun sendFile(file: File, mime: String? = null, kind: String = "file", source: String = "share"): SendReceipt {
        checkStarted()
        require(file.isFile) { "File to send was not found." }
        require(file.length() <= MAX_PLAINTEXT_BLOB_BYTES) { "TapDrop files are limited to 95 MiB." }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val contentType = mime ?: mimeFor(file.extension)
        val blob = uploadBlob(bytes, file.name, contentType)
        return sendItem(newItem(
            kind = if (contentType.startsWith("image/", ignoreCase = true)) "image" else kind,
            source = source,
        ).copy(blob = blob))
    }

    suspend fun stop() {
        socket.getAndSet(null)?.cancel()
        runJob?.cancelAndJoin()
        runJob = null
        _connectionState.value = RelayConnectionState()
    }

    override fun close() {
        socket.getAndSet(null)?.cancel()
        scope.coroutineContext[Job]?.cancel()
        http.dispatcher.cancelAll()
        thread(name = "TapDrop-close", isDaemon = true) {
            try {
                http.connectionPool.evictAll()
            } catch (error: Exception) {
                runCatching { log("TapDrop connection cleanup failed: ${error.message}") }
            }
            try {
                http.dispatcher.executorService.shutdown()
            } catch (error: Exception) {
                runCatching { log("TapDrop dispatcher cleanup failed: ${error.message}") }
            }
        }
    }

    suspend fun unpair() {
        val request = Request.Builder()
            .url(pairUrl(""))
            .header("Authorization", "Bearer ${pair.authToken}")
            .delete()
            .build()
        execute(request, STANDARD_TIMEOUT).use { response ->
            if (!response.isSuccessful) throw RelayHttpException(response.code, "Unpair failed")
        }
    }

    private suspend fun sendItem(item: Item): SendReceipt {
        val activeRole = checkStarted()
        val id = UUID.randomUUID().toString()
        val envelope = TapDropCrypto.encryptItem(pair, activeRole, id, item)
        val frame = buildJsonObject {
            put("t", "send")
            put("id", id)
            putJsonObject("env") {
                put("v", envelope.v)
                put("n", envelope.n)
                put("c", envelope.c)
            }
        }.toString()
        val queued = SendReceipt(id, _connectionState.value.connected)
        val pendingSend = PendingSend(id, frame)
        pending[id] = pendingSend
        try {
            outgoing.send(pendingSend)
            pendingSend.stored.await()
            return queued
        } finally {
            pending.remove(id)
        }
    }

    internal suspend fun uploadBlob(plaintext: ByteArray, name: String, mime: String): BlobRef {
        require(plaintext.size <= MAX_PLAINTEXT_BLOB_BYTES) { "TapDrop blobs are limited to 95 MiB." }
        val startedAt = System.nanoTime()
        var backoffMillis = 1000L
        var lastError: Exception? = null
        while (true) {
            val signal = uploadRetrySignal.get()
            var remainingNanos = TimeUnit.MINUTES.toNanos(15) - (System.nanoTime() - startedAt)
            if (remainingNanos <= 0) throw lastError ?: IOException("Blob upload retry window expired.")
            try {
                val timeoutMillis = minOf(BLOB_TIMEOUT, TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1))
                return uploadBlobAttempt(plaintext, name, mime, timeoutMillis)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isRetryableBlobUploadError(error)) throw error
                lastError = error
                remainingNanos = TimeUnit.MINUTES.toNanos(15) - (System.nanoTime() - startedAt)
                if (remainingNanos <= 0) throw error
                if (uploadRetrySignal.get().version != signal.version) {
                    backoffMillis = 1000L
                    continue
                }
                val jitter = 0.75 + Math.random() * 0.5
                val delayMillis = (backoffMillis * jitter).toLong().coerceAtLeast(1)
                val waitMillis = minOf(delayMillis, TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1))
                val woke = kotlinx.coroutines.withTimeoutOrNull(waitMillis) {
                    signal.ready.await()
                    true
                } ?: false
                backoffMillis = if (woke) 1000L else (backoffMillis * 2).coerceAtMost(30_000L)
            }
        }
    }

    internal fun isRetryableBlobUploadError(error: Exception): Boolean = when (error) {
        is RelayHttpException -> error.status == 408 || error.status == 429 || error.status in 500..599
        is IOException -> true
        else -> false
    }

    private suspend fun uploadBlobAttempt(
        plaintext: ByteArray,
        name: String,
        mime: String,
        timeoutMillis: Long,
    ): BlobRef {
        val id = TapDropCrypto.toBase64Url(ByteArray(16).also(java.security.SecureRandom()::nextBytes))
        val encrypted = TapDropCrypto.encryptBlob(pair, id, plaintext)
        val request = Request.Builder()
            .url(pairUrl("/blobs/$id"))
            .header("Authorization", "Bearer ${pair.authToken}")
            .put(encrypted.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        execute(request, timeoutMillis).use { response ->
            if (response.code != HttpURLConnection.HTTP_CREATED)
                throw RelayHttpException(response.code, "Blob upload failed")
        }
        return BlobRef(id, name, mime, plaintext.size.toLong(), TapDropCrypto.sha256Hex(plaintext))
    }

    private fun signalUploadRetries() {
        while (true) {
            val current = uploadRetrySignal.get()
            val replacement = UploadRetrySignal(current.version + 1, CompletableDeferred())
            if (uploadRetrySignal.compareAndSet(current, replacement)) {
                current.ready.complete(Unit)
                return
            }
        }
    }

    private suspend fun reconnectLoop(role: String) {
        var backoff = 1000L
        while (scope.isActive) {
            val connectedAt = System.nanoTime()
            try {
                runConnection(role)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                log("TapDrop relay connection: ${error.message}")
            } finally {
                _connectionState.value = RelayConnectionState()
            }
            if (!scope.isActive) break
            if (System.nanoTime() - connectedAt >= TimeUnit.SECONDS.toNanos(30)) backoff = 1000
            val jittered = (backoff * (0.75 + Math.random() * 0.5)).toLong().coerceAtLeast(1)
            kotlinx.coroutines.withTimeoutOrNull(jittered) { reconnectRequests.receive() }
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private suspend fun runConnection(role: String) = kotlinx.coroutines.coroutineScope {
        val messages = Channel<JsonObject>(Channel.UNLIMITED)
        val opened = CompletableDeferred<WebSocket>()
        val closed = CompletableDeferred<Throwable>()
        val request = Request.Builder()
            .url(webSocketUrl(role))
            .header("Authorization", "Bearer ${pair.authToken}")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(webSocket)
                _connectionState.value = RelayConnectionState(connected = true)
                signalUploadRetries()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = ProtocolJson.json.parseToJsonElement(text).jsonObject
                    when (frame["t"]?.jsonPrimitive?.content) {
                        "msg" -> messages.trySend(frame)
                        "hello", "presence" -> {
                            _connectionState.value = RelayConnectionState(
                                connected = true,
                                peerOnline = frame["peerOnline"]?.jsonPrimitive?.content == "true",
                            )
                        }
                        "pong" -> lastPongNanos.set(System.nanoTime())
                        "stored" -> frame["id"]?.jsonPrimitive?.content?.let { pending[it]?.stored?.complete(it) }
                        "delivered" -> frame["id"]?.jsonPrimitive?.content?.let { _delivered.tryEmit(it) }
                        "error" -> {
                            val id = frame["id"]?.jsonPrimitive?.content
                            if (id != null) {
                                val error = frame["code"]?.jsonPrimitive?.content ?: "relay_error"
                                pending[id]?.let {
                                    if (error == "duplicate_id") it.stored.complete(id)
                                    else it.stored.completeExceptionally(IllegalStateException("Relay rejected message: $error"))
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    closed.complete(error)
                    webSocket.close(1002, "Invalid relay frame")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                closed.complete(IOException("Relay closed the WebSocket: $code"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(IOException("Relay closed the WebSocket: $code"))
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                opened.completeExceptionally(error)
                closed.complete(error)
            }
        }

        val webSocket = http.newWebSocket(request, listener)
        socket.set(webSocket)
        try {
            withTimeout(STANDARD_TIMEOUT.milliseconds) { opened.await() }
            lastPongNanos.set(System.nanoTime())
            val worker = launch { for (frame in messages) processMessage(webSocket, frame) }
            val sender = launch { sendLoop(webSocket) }
            val pinger = launch { pingLoop(webSocket) }
            select<Unit> {
                closed.onAwait { throw it }
                worker.onJoin { throw IOException("Relay message worker stopped.") }
                sender.onJoin { throw IOException("Relay send worker stopped.") }
                pinger.onJoin { throw IOException("Relay ping worker stopped.") }
            }
        } finally {
            socket.compareAndSet(webSocket, null)
            messages.close()
            webSocket.cancel()
        }
    }

    private suspend fun processMessage(socket: WebSocket, frame: JsonObject) {
        val id = frame["id"]?.jsonPrimitive?.contentOrNull ?: return
        val from = frame["from"]?.jsonPrimitive?.contentOrNull ?: return
        if (from != "phone" && from != "laptop") return
        synchronized(dedupeLock) {
            if (id in recentIds) {
                handlerFailures.remove(id)
                sendFrame(socket, ackFrame(id))
                return
            }
            remember(id)
        }

        var blobId: String? = null
        var tempFile: File? = null
        var decodedItem: Item? = null
        try {
            try {
                val envelope = ProtocolJson.json.decodeFromJsonElement<Envelope>(
                    frame["env"] ?: throw PermanentMessageError("Message has no envelope."),
                )
                var item = try {
                    TapDropCrypto.decryptItem(pair, from, id, envelope)
                } catch (error: IllegalArgumentException) {
                    throw PermanentMessageError("Invalid encrypted message.", error)
                }
                if (item.kind !in setOf("text", "url", "image", "file"))
                    throw PermanentMessageError("Unknown item kind: ${item.kind}.")
                if (isHandlerPoisoned(id)) {
                    blobId = item.blob?.id
                    sendFrame(socket, ackFrame(id))
                    clearHandlerFailures(id)
                    log("Dropped TapDrop message $id after 3 handler failures.")
                    blobId?.let { poisonedBlobId ->
                        try { deleteBlob(poisonedBlobId) }
                        catch (cleanup: Exception) { log("TapDrop blob cleanup failed: ${cleanup.message}") }
                    }
                    return
                }
                val blob = item.blob
                if (blob != null) {
                    blobId = blob.id
                    if (blob.size < 0 || blob.size > MAX_PLAINTEXT_BLOB_BYTES)
                        throw PermanentMessageError("Invalid blob size.")
                    if (!blob.id.matches(BLOB_ID_PATTERN)) throw PermanentMessageError("Invalid blob id.")
                    val request = Request.Builder()
                        .url(pairUrl("/blobs/${blob.id}"))
                        .header("Authorization", "Bearer ${pair.authToken}")
                        .get()
                        .build()
                    execute(request, BLOB_TIMEOUT).use { response ->
                        if (response.code == HttpURLConnection.HTTP_NOT_FOUND)
                            throw PermanentMessageError("Referenced blob ${blob.id} was not found.")
                        if (!response.isSuccessful) throw RelayHttpException(response.code, "Blob download failed")
                        val encrypted = response.body?.bytes() ?: throw IOException("Blob response has no body.")
                        val plaintext = try {
                            TapDropCrypto.decryptBlob(pair, blob.id, encrypted)
                        } catch (error: IllegalArgumentException) {
                            throw PermanentMessageError("Invalid encrypted blob.", error)
                        }
                        if (plaintext.size.toLong() != blob.size || !MessageDigest.isEqual(
                                TapDropCrypto.sha256Hex(plaintext).toByteArray(Charsets.US_ASCII),
                                blob.sha256.lowercase().toByteArray(Charsets.US_ASCII),
                            )
                        ) throw PermanentMessageError("Received blob size or SHA-256 does not match.")
                        cacheDirectory.mkdirs()
                        tempFile = File.createTempFile("tapdrop-", ".blob", cacheDirectory).apply { writeBytes(plaintext) }
                        if (item.kind == "text") item = item.copy(text = plaintext.toString(Charsets.UTF_8))
                    }
                }
                decodedItem = item
            } catch (error: Exception) {
                if (isPermanent(error)) {
                    log("Dropped TapDrop message $id: ${error.message}")
                    sendFrame(socket, ackFrame(id))
                    blobId?.let { try { deleteBlob(it) } catch (cleanup: Exception) { log("TapDrop blob cleanup failed: ${cleanup.message}") } }
                    return
                }
                synchronized(dedupeLock) { forget(id) }
                throw error
            }
            try {
                handler?.invoke(ReceivedItem(id, from, decodedItem ?: throw IllegalStateException("Message was not decoded."), tempFile))
                clearHandlerFailures(id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                synchronized(dedupeLock) { forget(id) }
                throw error
            } catch (error: Exception) {
                if (recordHandlerFailure(id) >= 3) {
                    sendFrame(socket, ackFrame(id))
                    clearHandlerFailures(id)
                    log("Dropped TapDrop message $id after 3 handler failures: ${error.message}")
                    blobId?.let { poisonedBlobId ->
                        try { deleteBlob(poisonedBlobId) }
                        catch (cleanup: Exception) { log("TapDrop blob cleanup failed: ${cleanup.message}") }
                    }
                    return
                }
                synchronized(dedupeLock) { forget(id) }
                throw error
            }
            sendFrame(socket, ackFrame(id))
            blobId?.let { deleteBlob(it) }
        } finally {
            tempFile?.delete()
        }
    }

    private suspend fun sendLoop(socket: WebSocket) {
        while (scope.isActive) {
            val next = outgoing.receive()
            if (next.stored.isCompleted) continue
            try {
                if (!socket.send(next.frame)) throw IOException("Relay WebSocket rejected a frame.")
                withTimeout(STORED_TIMEOUT.milliseconds) { next.stored.await() }
            } catch (error: Exception) {
                if (!next.stored.isCompleted) outgoing.trySend(next)
                throw error
            }
        }
    }

    private suspend fun pingLoop(socket: WebSocket) {
        val pingInterval = settingSeconds("TAPDROP_PING_INTERVAL_SECONDS", 25)
        val pongTimeout = settingSeconds("TAPDROP_PONG_TIMEOUT_SECONDS", 10)
        while (scope.isActive) {
            kotlinx.coroutines.delay(pingInterval)
            val pingSent = System.nanoTime()
            if (!socket.send("""{"t":"ping"}""")) throw IOException("Relay ping could not be sent.")
            kotlinx.coroutines.delay(pongTimeout)
            if (lastPongNanos.get() < pingSent) throw SocketTimeoutException("Relay pong timed out.")
        }
    }

    private suspend fun deleteBlob(blobId: String) {
        val request = Request.Builder()
            .url(pairUrl("/blobs/$blobId"))
            .header("Authorization", "Bearer ${pair.authToken}")
            .delete()
            .build()
        execute(request, STANDARD_TIMEOUT).use { response ->
            if (!response.isSuccessful) throw RelayHttpException(response.code, "Blob deletion failed")
        }
    }

    private suspend fun execute(request: Request, timeoutMillis: Long): Response = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        call.execute()
    }

    private fun sendFrame(socket: WebSocket, frame: String) {
        if (!socket.send(frame)) throw IOException("Relay WebSocket rejected a frame.")
    }

    private fun ackFrame(id: String): String = buildJsonObject {
        put("t", "ack")
        put("id", id)
    }.toString()

    private fun pairUrl(path: String) = "${pair.relayUrl.trimEnd('/')}/v1/pairs/${pair.pairId}$path".toHttpUrl()

    private fun webSocketUrl(role: String): okhttp3.HttpUrl {
        return "${pair.relayUrl.trimEnd('/')}/v1/pairs/${pair.pairId}/ws?role=$role".toHttpUrl()
    }

    private fun newItem(
        kind: String,
        source: String,
        text: String? = null,
        url: String? = null,
    ) = Item(kind, text, url, source = source, createdAt = System.currentTimeMillis(), sender = pair.deviceName)

    private fun checkStarted(): String {
        check(runJob?.isActive == true && role != null) { "Start the relay client before sending." }
        return role!!
    }

    private fun isPermanent(error: Exception): Boolean =
        error is PermanentMessageError || error is GeneralSecurityException ||
            error is SerializationException

    private fun remember(id: String) {
        recentIds.add(id)
        recentOrder.addLast(id)
        while (recentOrder.size > RECENT_ID_LIMIT) {
            val expired = recentOrder.removeFirst()
            recentIds.remove(expired)
            handlerFailures.remove(expired)
        }
    }

    private fun forget(id: String) {
        if (!recentIds.remove(id)) return
        recentOrder.remove(id)
    }

    private fun recordHandlerFailure(id: String): Int = synchronized(dedupeLock) {
        val count = (handlerFailures[id] ?: 0) + 1
        handlerFailures[id] = count
        while (handlerFailures.size > RECENT_ID_LIMIT)
            handlerFailures.remove(handlerFailures.keys.first())
        count
    }

    private fun isHandlerPoisoned(id: String): Boolean = synchronized(dedupeLock) {
        (handlerFailures[id] ?: 0) >= 3
    }

    private fun clearHandlerFailures(id: String) {
        synchronized(dedupeLock) { handlerFailures.remove(id) }
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private data class UploadRetrySignal(val version: Long, val ready: CompletableDeferred<Unit>)

    private class PendingSend(val id: String, val frame: String) {
        val stored = CompletableDeferred<String>()
    }

    private class PermanentMessageError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class RelayHttpException(val status: Int, message: String) : IOException("$message: HTTP $status")

    companion object {
        private const val INLINE_TEXT_BYTES = 32 * 1024
        private const val MAX_PLAINTEXT_BLOB_BYTES = 95L * 1024 * 1024
        private const val RECENT_ID_LIMIT = 1000
        private const val STANDARD_TIMEOUT = 30_000L
        private const val BLOB_TIMEOUT = 10 * 60_000L
        private const val STORED_TIMEOUT = 15_000L
        private val BLOB_ID_PATTERN = Regex("[A-Za-z0-9_-]{22}")
    }
}

internal fun settingSeconds(
    name: String,
    default: Long,
    environment: (String) -> String? = System::getenv,
): Duration =
    environment(name)?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(3600)?.seconds ?: default.seconds
