package com.mtracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nid = i.getIntExtra(Extras.NOTIF_ID, -1)
        val pid = i.getLongExtra(Extras.PENDING, 0L)
        val nm = NotificationManagerCompat.from(c)
        // Wrong call is safe even with App Lock: it only deletes a prompt, never a ledger row.
        if (i.action == Extras.ACTION_WRONG) {
            nm.cancel(nid)
            i.getStringExtra(Extras.PKG)?.let { AppPrefs.get(c).bumpWrong(it) }
            if (pid != 0L) async(c) { it.dao().deletePending(pid) }
            return
        }
        // Defense-in-depth: no background ledger mutation while App Lock is enabled.
        if (AppPrefs.get(c).lockEnabled.value) {
            nm.cancel(nid)
            return
        }
        when (i.action) {
            // Evening check-in: confirm a zero-spend day (keeps the streak alive).
            Extras.ACTION_NO_SPEND -> {
                nm.cancel(nid)
                async(c) { it.dao().insertMark(DayMark(today())) }
            }
        }
    }

    private fun async(c: Context, block: suspend (AppDatabase) -> Unit) {
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { block(AppDatabase.get(c)) } finally { pr.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            MonitorController.sync(c)
            UpdateScheduler.sync(c)
            CheckinScheduler.sync(c)
        }
    }
}
