package dev.peytonli.tapdrop.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.peytonli.tapdrop.R
import dev.peytonli.tapdrop.core.PairInfo
import dev.peytonli.tapdrop.core.RelayClient
import dev.peytonli.tapdrop.core.RelayConnectionState
import dev.peytonli.tapdrop.core.ReceivedItem
import dev.peytonli.tapdrop.core.ShareText
import dev.peytonli.tapdrop.pairing.PairingRecord
import dev.peytonli.tapdrop.pairing.PairingStorage
import dev.peytonli.tapdrop.pairing.RecentEntry
import dev.peytonli.tapdrop.pairing.RecentStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RelayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pairingStorage: PairingStorage
    private var pairing: PairingRecord? = null
    private var client: RelayClient? = null
    private var networkManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        pairingStorage = PairingStorage(this)
        createChannels()
        startForegroundStatus()
        connect()
        observeNetworkChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> serviceScope.launch { sendSharedContent(intent) }
            ACTION_STOP -> stopSelf()
            ACTION_START, null -> connect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let { callback -> networkManager?.unregisterNetworkCallback(callback) }
        client?.close()
        client = null
        serviceScope.cancel()
        _status.value = RelayConnectionState()
        super.onDestroy()
    }

    private fun connect() {
        val saved = pairingStorage.load() ?: return
        if (client != null) return
        pairing = saved
        val relayClient = RelayClient(saved.pair, cacheDir) { android.util.Log.w("TapDrop", it) }
        client = relayClient
        relayClient.start("phone") { received -> receive(saved, received) }
        serviceScope.launch {
            relayClient.connectionState.collect { state ->
                _status.value = state
                val description = when {
                    state.peerOnline -> "Connected — laptop online"
                    state.connected -> "Connected — laptop offline"
                    else -> "Connecting to laptop"
                }
                updateForegroundNotification(description)
            }
        }
    }

    private fun observeNetworkChanges() {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkManager = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                client?.reconnectNow()
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private suspend fun sendSharedContent(intent: Intent) {
        val saved = pairing ?: pairingStorage.load()
        val relayClient = client
        if (saved == null || relayClient == null) {
            showToast("Pair TapDrop with your laptop first")
            stopSelf()
            return
        }
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "share"
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
        val disconnected = !relayClient.connectionState.value.connected
        if (disconnected) showToast("Queued — will send when online")
        try {
            if (!text.isNullOrBlank()) {
                val parsed = ShareText.parse(text)
                if (parsed.kind == "url") relayClient.sendUrl(parsed.url.orEmpty(), source)
                else relayClient.sendText(parsed.text.orEmpty(), source)
            }
            for (uri in uris) {
                val cached = copySharedUri(uri)
                try {
                    relayClient.sendFile(cached.file, cached.mime, source = source)
                } finally {
                    cached.file.delete()
                }
            }
            if (!disconnected) showToast("Sent to ${saved.laptopName}")
        } catch (error: Exception) {
            android.util.Log.e("TapDrop", "Sending shared content failed", error)
            showToast(error.message ?: "TapDrop could not send the item")
        }
    }

    private suspend fun copySharedUri(uri: Uri): CachedShare = withContext(Dispatchers.IO) {
        val rawName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "shared-item"
        val displayName = File(rawName).name.ifBlank { "shared-item" }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val file = File.createTempFile("tapdrop-share-", ".cache", cacheDir)
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open shared content.")
            input.use { source ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BLOB_BYTES) throw IOException("TapDrop files are limited to 95 MiB.")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val named = File(cacheDir, "${System.nanoTime()}-$displayName")
            if (!file.renameTo(named)) throw IOException("Could not cache shared content.")
            CachedShare(named, mime)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private suspend fun receive(saved: PairingRecord, received: ReceivedItem) {
        val item = received.item
        when (item.kind) {
            "text" -> {
                val text = item.text.orEmpty()
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TapDrop", text))
                }
                RecentStore(this).add(RecentEntry("text", text, sender = item.sender, createdAt = item.createdAt))
                postReceivedNotification(received.messageId.hashCode(), "Copied from ${saved.laptopName}", "Text copied to clipboard", shareIntent(text))
            }
            "url" -> {
                val url = item.url.orEmpty()
                RecentStore(this).add(RecentEntry("url", url, sender = item.sender, createdAt = item.createdAt))
                postUrlNotification(received.messageId.hashCode(), saved.laptopName, url)
            }
            "image", "file" -> {
                val source = received.blobFile ?: throw IOException("Received blob has no temporary file.")
                val uri = saveToMediaStore(source, item.blob?.name ?: received.messageId, item.blob?.mime ?: "application/octet-stream", item.kind)
                RecentStore(this).add(RecentEntry(item.kind, uri = uri.toString(), sender = item.sender, createdAt = item.createdAt))
                postBlobNotification(received.messageId.hashCode(), saved.laptopName, item.kind, item.blob?.name ?: "TapDrop item", uri, source)
            }
        }
    }

    private suspend fun saveToMediaStore(file: File, name: String, mime: String, kind: String): Uri =
        withContext(Dispatchers.IO) {
            val images = kind == "image"
            val collection = if (images) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relativePath = if (images) "${android.os.Environment.DIRECTORY_PICTURES}/TapDrop"
            else "${android.os.Environment.DIRECTORY_DOWNLOADS}/TapDrop"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values) ?: throw IOException("Could not save received item.")
            try {
                contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: throw IOException("Could not open received item for writing.")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        }

    private fun postUrlNotification(id: Int, laptopName: String, url: String) {
        val open = PendingIntent.getActivity(
            this, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = PendingIntent.getBroadcast(
            this, id + 1, Intent(this, CopyTextReceiver::class.java).putExtra(EXTRA_COPY_TEXT, url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, RECEIVED_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Link from $laptopName")
            .setContentText(url)
            .setContentIntent(open)
            .addAction(0, "Copy", copy)
            .setAutoCancel(true)
            .build()
        notifyReceived(id, notification)
    }

    private fun postBlobNotification(id: Int, laptopName: String, kind: String, name: String, uri: Uri, file: File) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val open = PendingIntent.getActivity(
            this, id, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, RECEIVED_CHANNEL)
            .setSmallIcon(if (kind == "image") android.R.drawable.ic_menu_gallery else android.R.drawable.ic_menu_save)
            .setContentTitle("$kind from $laptopName")
            .setContentText(name)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (kind == "image") {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = maxOf(
                1,
                Integer.highestOneBit(maxOf(options.outWidth / 512, options.outHeight / 512).coerceAtLeast(1)),
            )
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bitmap != null) builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as android.graphics.Bitmap?))
        }
        notifyReceived(id, builder.build())
    }

    private fun postReceivedNotification(id: Int, title: String, message: String, share: PendingIntent) {
        val notification = NotificationCompat.Builder(this, RECEIVED_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(message)
            .addAction(0, "Share", share)
            .setAutoCancel(true)
            .build()
        notifyReceived(id, notification)
    }

    private fun shareIntent(text: String): PendingIntent {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return PendingIntent.getActivity(
            this, text.hashCode(), Intent.createChooser(share, "Share text"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyReceived(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "TapDrop connection", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RECEIVED_CHANNEL, "Received items", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun startForegroundStatus() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, STATUS_ID, statusNotification("Connecting to laptop"), type)
    }

    private fun updateForegroundNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(STATUS_ID, statusNotification(text))
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TapDrop")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private data class CachedShare(val file: File, val mime: String)

    companion object {
        const val ACTION_START = "dev.peytonli.tapdrop.START"
        const val ACTION_SEND = "dev.peytonli.tapdrop.SEND"
        const val ACTION_STOP = "dev.peytonli.tapdrop.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_URIS = "uris"
        const val EXTRA_COPY_TEXT = "copyText"
        private const val STATUS_CHANNEL = "tapdrop-status"
        private const val RECEIVED_CHANNEL = "tapdrop-received"
        private const val STATUS_ID = 1201
        private const val MAX_BLOB_BYTES = 95L * 1024 * 1024
        private val _status = MutableStateFlow(RelayConnectionState())
        val status = _status.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java).setAction(ACTION_START))
        }

        fun send(context: Context, text: String?, uris: List<Uri>, source: String) {
            val intent = Intent(context, RelayService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(EXTRA_SOURCE, source)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty()) {
                val clip = android.content.ClipData.newUri(context.contentResolver, "TapDrop shared content", uris.first())
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                intent.clipData = clip
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun unpair(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
            _status.value = RelayConnectionState()
        }
    }
}

class CopyTextReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(RelayService.EXTRA_COPY_TEXT) ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TapDrop", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (PairingStorage(context).load() != null) RelayService.start(context)
    }
}
