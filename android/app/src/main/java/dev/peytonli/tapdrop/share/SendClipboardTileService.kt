package dev.peytonli.tapdrop.share

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.peytonli.tapdrop.service.RelayService

class SendClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val state = RelayService.status.value
        qsTile.label = "Send to laptop"
        qsTile.subtitle = when {
            state.peerOnline -> "Laptop online"
            state.connected -> "Connected"
            else -> "Connecting"
        }
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        RelayService.start(this)
        val intent = Intent(this, ClipboardSendActivity::class.java)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
