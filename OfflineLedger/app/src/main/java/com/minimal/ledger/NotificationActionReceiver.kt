package com.minimal.ledger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** "Wrong Call": silently dismiss, log nothing, reset the cooldown. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Extras.ACTION_WRONG) return
        NotificationManagerCompat.from(c).cancel(i.getIntExtra(Extras.NOTIF_ID, -1))
        i.getStringExtra(Extras.PKG)?.let { Cooldown.reset(it) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) MonitorController.sync(c)
    }
}
