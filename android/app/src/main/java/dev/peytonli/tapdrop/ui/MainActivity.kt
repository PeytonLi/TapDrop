package dev.peytonli.tapdrop.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.peytonli.tapdrop.core.RelayClient
import dev.peytonli.tapdrop.pairing.PairingRecord
import dev.peytonli.tapdrop.pairing.PairingStorage
import dev.peytonli.tapdrop.pairing.RecentEntry
import dev.peytonli.tapdrop.pairing.RecentStore
import dev.peytonli.tapdrop.pairing.PairScannerActivity
import dev.peytonli.tapdrop.service.RelayService
import dev.peytonli.tapdrop.share.SendClipboardTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val pairingStorage by lazy { PairingStorage(this) }
    private val recentStore by lazy { RecentStore(this) }
    private var refreshScreen: (() -> Unit)? = null
    private val pairLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshScreen?.invoke()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingStorage.load()?.let { RelayService.start(this) }
        setContent {
            var paired by remember { mutableStateOf(pairingStorage.load()) }
            var recent by remember { mutableStateOf(recentStore.load()) }
            val connection by RelayService.status.collectAsState()
            refreshScreen = {
                paired = pairingStorage.load()
                recent = recentStore.load()
                if (paired != null) RelayService.start(this)
            }
            TapDropTheme {
                MainScreen(
                    paired = paired,
                    recent = recent,
                    connected = connection.connected,
                    peerOnline = connection.peerOnline,
                    onPair = { pairLauncher.launch(Intent(this, PairScannerActivity::class.java)) },
                    onUnpair = { unpair() },
                    onNotifications = { requestNotifications() },
                    onBattery = { requestBatteryExemption() },
                    onAddTile = { requestTile() },
                    onRecent = ::openRecent,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshScreen?.invoke()
    }

    private fun unpair() {
        val saved = pairingStorage.load() ?: return
        lifecycleScope.launch {
            val client = RelayClient(saved.pair, cacheDir)
            try {
                withContext(Dispatchers.IO) { client.unpair() }
                pairingStorage.clear()
                RelayService.unpair(this@MainActivity)
                refreshScreen?.invoke()
                Toast.makeText(this@MainActivity, "Unpaired from ${saved.laptopName}", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Could not unpair", Toast.LENGTH_LONG).show()
            } finally {
                client.close()
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Toast.makeText(this, "Notifications are enabled", Toast.LENGTH_SHORT).show()
    }

    private fun requestBatteryExemption() {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        if (manager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "TapDrop is unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Add TapDrop from Quick Settings edit mode", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, SendClipboardTileService::class.java),
            "Send to laptop",
            Icon.createWithResource(this, android.R.drawable.ic_menu_send),
            mainExecutor,
        ) { }
    }

    private fun openRecent(entry: RecentEntry) {
        try {
            when (entry.kind) {
                "text" -> {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TapDrop", entry.text.orEmpty()))
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                "url" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.text)))
                else -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.uri), contentResolver.getType(Uri.parse(entry.uri))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not open recent item", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun TapDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF5366D8),
            background = Color(0xFFF6F7FB),
            surface = Color.White,
        ),
        content = content,
    )
}

@Composable
private fun MainScreen(
    paired: PairingRecord?,
    recent: List<RecentEntry>,
    connected: Boolean,
    peerOnline: Boolean,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onAddTile: () -> Unit,
    onRecent: (RecentEntry) -> Unit,
) {
    val context = LocalContext.current
    val manager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val batteryUnrestricted = manager.isIgnoringBatteryOptimizations(context.packageName)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.padding(top = 28.dp, bottom = 4.dp)) {
                    Text("TapDrop", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Private sharing between your phone and laptop", color = Color(0xFF596174))
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("YOUR LAPTOP", style = MaterialTheme.typography.labelMedium, color = Color(0xFF656D80))
                        Text(paired?.laptopName ?: "No laptop paired", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                paired == null -> "Scan your laptop's QR code to get started"
                                peerOnline -> "Laptop online"
                                connected -> "Relay connected · laptop offline"
                                else -> "Connecting through the relay"
                            },
                            color = if (peerOnline) Color(0xFF18845B) else Color(0xFF596174),
                        )
                        Spacer(Modifier.height(2.dp))
                        if (paired == null) {
                            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text("Pair with laptop") }
                        } else {
                            OutlinedButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) { Text("Unpair") }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        SetupRow("Notifications", if (notificationsGranted) "Enabled" else "Allow received-item alerts", onNotifications)
                        SetupRow(
                            "Battery: ${if (batteryUnrestricted) "Unrestricted" else "Optimize TapDrop"}",
                            "Samsung: Settings → Apps → TapDrop → Battery → Unrestricted",
                            onBattery,
                        )
                        SetupRow("Quick Settings tile", "Add Send to laptop for one-tap clipboard sharing", onAddTile)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${recent.size}/20", color = Color(0xFF596174))
                }
            }
            if (recent.isEmpty()) {
                item {
                    Text("Your received items will appear here.", color = Color(0xFF596174), modifier = Modifier.padding(bottom = 30.dp))
                }
            } else {
                items(recent) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onRecent(entry) },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(15.dp)) {
                            Text(entry.kind.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                            Text(entry.text ?: entry.uri?.substringAfterLast('/') ?: "Tap to open", maxLines = 2, color = Color(0xFF596174))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF596174))
        }
        Text("Set up", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}
