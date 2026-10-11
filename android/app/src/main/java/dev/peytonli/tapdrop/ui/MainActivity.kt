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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.peytonli.tapdrop.core.TapConfig
import dev.peytonli.tapdrop.pairing.PairingRecord
import dev.peytonli.tapdrop.pairing.PairingStorage
import dev.peytonli.tapdrop.pairing.RecentEntry
import dev.peytonli.tapdrop.pairing.RecentStore
import dev.peytonli.tapdrop.pairing.PairScannerActivity
import dev.peytonli.tapdrop.service.RelayService
import dev.peytonli.tapdrop.service.TapDropAccessibilityService
import dev.peytonli.tapdrop.service.TapPreferenceState
import dev.peytonli.tapdrop.service.TapSensorSnapshot
import dev.peytonli.tapdrop.service.TapSensorStatus
import dev.peytonli.tapdrop.service.TapSettings
import dev.peytonli.tapdrop.share.SendClipboardTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LoadedMainState(
    val pairing: PairingRecord?,
    val recent: List<RecentEntry>,
    val tapSettings: TapSettings,
    val tapPreferences: TapPreferenceState,
)

class MainActivity : ComponentActivity() {
    private val pairingStorage by lazy { PairingStorage(this) }
    private val recentStore by lazy { RecentStore(this) }
    private val pairingState = MutableStateFlow<PairingRecord?>(null)
    private val recentState = MutableStateFlow<List<RecentEntry>>(emptyList())
    private val tapPreferenceState = MutableStateFlow(TapPreferenceState())
    private var tapSettings: TapSettings? = null
    private val pairLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshScreen()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val photoPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val paired by pairingState.collectAsState()
            val recent by recentState.collectAsState()
            val tapPreferences by tapPreferenceState.collectAsState()
            var calibration by remember { mutableStateOf(false) }
            val sensorSnapshot by TapSensorStatus.state.collectAsState()
            val connection by RelayService.status.collectAsState()
            TapDropTheme {
                if (calibration) {
                    CalibrationScreen(
                        snapshot = sensorSnapshot,
                        config = tapPreferences.config,
                        dryRun = tapPreferences.dryRun,
                        onBack = { calibration = false },
                        onConfigChange = {
                            tapSettings?.let { settings ->
                                settings.setConfig(it)
                                tapPreferenceState.value = tapPreferenceState.value.copy(config = it)
                            }
                        },
                        onDryRunChange = {
                            tapSettings?.let { settings ->
                                settings.setDryRun(it)
                                tapPreferenceState.value = tapPreferenceState.value.copy(dryRun = it)
                            }
                        },
                    )
                } else {
                    MainScreen(
                        paired = paired,
                        recent = recent,
                        connected = connection.connected,
                        peerOnline = connection.peerOnline,
                        magnetEnabled = tapPreferences.enabled,
                        onMagnetEnabled = {
                            tapSettings?.let { settings ->
                                settings.setEnabled(it)
                                tapPreferenceState.value = tapPreferenceState.value.copy(enabled = it)
                                if (it && paired != null) RelayService.start(this)
                            }
                        },
                        onCalibration = { calibration = true },
                        onAccessibility = { openAccessibilitySettings() },
                        onPhotos = { requestPhotos() },
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
    }

    override fun onResume() {
        super.onResume()
        refreshScreen()
    }

    private fun refreshScreen() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val settings = TapSettings(this@MainActivity)
                LoadedMainState(
                    pairingStorage.load(),
                    recentStore.load(),
                    settings,
                    settings.snapshot(),
                )
            }
            tapSettings = loaded.tapSettings
            tapPreferenceState.value = loaded.tapPreferences
            pairingState.value = loaded.pairing
            recentState.value = loaded.recent
            if (loaded.pairing != null) RelayService.start(this@MainActivity)
        }
    }

    private fun unpair() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { pairingStorage.load() } ?: return@launch
            val client = RelayClient(saved.pair, cacheDir)
            try {
                withContext(Dispatchers.IO) {
                    client.unpair()
                    pairingStorage.clear()
                }
                RelayService.unpair(this@MainActivity)
                refreshScreen()
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

    private fun requestPhotos() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        photoPermission.launch(permissions)
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(
            this,
            "On Android 13+, open TapDrop App info → ⋮ → Allow restricted settings, then enable TapDrop accessibility.",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                else -> lifecycleScope.launch {
                    try {
                        val uri = Uri.parse(entry.uri)
                        val mime = withContext(Dispatchers.IO) { contentResolver.getType(uri) }
                        startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    } catch (error: Exception) {
                        Toast.makeText(this@MainActivity, error.message ?: "Could not open recent item", Toast.LENGTH_SHORT).show()
                    }
                }
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
    magnetEnabled: Boolean,
    onMagnetEnabled: (Boolean) -> Unit,
    onCalibration: () -> Unit,
    onAccessibility: () -> Unit,
    onPhotos: () -> Unit,
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
    val accessibilityEnabled = TapDropAccessibilityService.isEnabled(context)
    val photosGranted = if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

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
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Magnet tap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Tap your phone to the laptop edge to send", color = Color(0xFF596174))
                            }
                            Switch(checked = magnetEnabled, onCheckedChange = onMagnetEnabled)
                        }
                        OutlinedButton(onClick = onCalibration, modifier = Modifier.fillMaxWidth()) {
                            Text("Calibrate magnet tap")
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
                        SetupRow(
                            "Accessibility: ${if (accessibilityEnabled) "Enabled" else "Enable tap-to-send"}",
                            "Reads a supported browser URL or captures the screen only after a tap. Android 13+: App info → ⋮ → Allow restricted settings.",
                            onAccessibility,
                        )
                        SetupRow(
                            "Photos: ${if (photosGranted) "Full access enabled" else "Allow full photo access"}",
                            "TapDrop skips selected-only photo access and sends a recent image only.",
                            onPhotos,
                        )
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

@Composable
private fun CalibrationScreen(
    snapshot: TapSensorSnapshot,
    config: TapConfig,
    dryRun: Boolean,
    onBack: () -> Unit,
    onConfigChange: (TapConfig) -> Unit,
    onDryRunChange: (Boolean) -> Unit,
) {
    val detector = snapshot.detector
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text("Magnet calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Live sensors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("|B| ${"%.1f".format(detector.magnitudeUt)} µT · baseline ${"%.1f".format(detector.baselineUt)} µT")
                        Text("Near: ${if (detector.near) "yes" else "no"} · acceleration ${"%.2f".format(detector.linearMagnitudeMs2)} m/s²")
                        Text(
                            when {
                                !snapshot.sensorAvailable -> "Required sensors unavailable"
                                snapshot.listening -> "Listening while laptop is online and screen is unlocked"
                                else -> "Waiting for laptop online, screen unlocked, or Magnet tap enabled"
                            },
                            color = Color(0xFF596174),
                        )
                    }
                }
            }
            item { CalibrationSlider("Magnet delta", config.deltaUt, "µT", 25f..500f) { onConfigChange(config.copy(deltaUt = it)) } }
            item { CalibrationSlider("Absolute field", config.absoluteUt, "µT", 100f..700f) { onConfigChange(config.copy(absoluteUt = it)) } }
            item { CalibrationSlider("Bump threshold", config.bumpMs2, "m/s²", 0.5f..10f) { onConfigChange(config.copy(bumpMs2 = it)) } }
            item {
                val value = config.windowMs.toFloat()
                CalibrationSlider("Bump window", value, "ms", 50f..1000f) {
                    onConfigChange(config.copy(windowMs = it.toLong()))
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dry run", fontWeight = FontWeight.SemiBold)
                            Text("Show the URL or image filename without sending", color = Color(0xFF596174))
                        }
                        Switch(checked = dryRun, onCheckedChange = onDryRunChange)
                    }
                }
            }
            item { Text("Last 10 triggers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (snapshot.triggers.isEmpty()) {
                item { Text("No triggers yet. Keep the screen unlocked with the laptop online.", color = Color(0xFF596174)) }
            } else {
                items(snapshot.triggers) { trigger ->
                    Text(
                        "|B| ${"%.1f".format(trigger.magnitudeUt)} µT · bump ${"%.2f".format(trigger.linearMagnitudeMs2)} m/s²",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationSlider(
    title: String,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("$title: ${"%.1f".format(value)} $unit", fontWeight = FontWeight.Medium)
            Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
        }
    }
}
