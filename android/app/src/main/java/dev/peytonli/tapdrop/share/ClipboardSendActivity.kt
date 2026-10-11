package dev.peytonli.tapdrop.share

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.peytonli.tapdrop.service.RelayService

class ClipboardSendActivity : ComponentActivity() {
    private var consumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || consumed) return
        consumed = true
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val uri = item?.uri
        val text = item?.text?.toString()
        if (uri != null) RelayService.send(this, null, listOf(uri), "tile")
        else if (!text.isNullOrBlank()) RelayService.send(this, text, emptyList(), "tile")
        finish()
    }
}
