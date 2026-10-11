package dev.peytonli.tapdrop.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.peytonli.tapdrop.service.RelayService

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = collectUris()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        RelayService.send(this, text, uris, "share")
        finish()
    }

    private fun collectUris(): List<Uri> {
        val result = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(result::add)
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val streams = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            streams?.let(result::addAll)
        } else {
            @Suppress("DEPRECATION")
            val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            stream?.let(result::add)
        }
        return result.toList()
    }
}
