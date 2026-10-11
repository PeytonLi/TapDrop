package dev.peytonli.tapdrop.pairing

import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.peytonli.tapdrop.core.PairInfo
import dev.peytonli.tapdrop.core.RelayClient
import dev.peytonli.tapdrop.service.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairScannerActivity : ComponentActivity() {
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            finish()
        } else {
            pair(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the laptop's TapDrop QR"))
        }
    }

    private fun pair(contents: String) {
        lifecycleScope.launch {
            var client: RelayClient? = null
            try {
                val scanned = PairInfo.parseQrPayload(contents)
                val pair = scanned.copy(deviceName = Build.MODEL.ifBlank { "Android phone" })
                client = RelayClient(pair, cacheDir)
                withContext(Dispatchers.IO) { client.register() }
                withContext(Dispatchers.IO) {
                    PairingStorage(this@PairScannerActivity).save(pair, scanned.deviceName)
                }
                RelayService.start(this@PairScannerActivity)
                Toast.makeText(this@PairScannerActivity, "Paired with ${scanned.deviceName}", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
            } catch (error: Exception) {
                Toast.makeText(this@PairScannerActivity, error.message ?: "Pairing failed", Toast.LENGTH_LONG).show()
            } finally {
                client?.close()
                finish()
            }
        }
    }
}
