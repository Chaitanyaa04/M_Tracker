package com.mtracker.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nid = i.getIntExtra(Extras.NOTIF_ID, -1)
        val pid = i.getLongExtra(Extras.PENDING, 0L)
        val acc = i.getLongExtra(Extras.ACCOUNT, 0L)
        val nm = NotificationManagerCompat.from(c)
        // Defense-in-depth: never mutate ledger data from a notification action while App Lock is enabled.
        if (AppPrefs.get(c).lockEnabled.value) {
            nm.cancel(nid)
            return
        }
        when (i.action) {
            // Wrong call: forget this prompt and teach the detector to be a little less eager for this app.
            // The quiet period is NOT reset, so it can't ping again straight away.
            Extras.ACTION_WRONG -> {
                nm.cancel(nid)
                i.getStringExtra(Extras.PKG)?.let { AppPrefs.get(c).bumpWrong(it) }
                if (pid != 0L) async(c) { it.dao().deletePending(pid) }
            }
            // One tap: log the same expense as last time on this account.
            Extras.ACTION_SAME -> {
                nm.cancel(nid)
                async(c) { db ->
                    val d = db.dao()
                    val last = d.lastExpenseFor(acc)
                    if (last != null) {
                        d.insert(last.copy(id = 0, date = today(), createdAt = System.currentTimeMillis(), personId = 0, owed = 0.0, items = "", routeFrom = "", routeTo = "", tripId = 0))
                        if (pid != 0L) d.deletePending(pid)
                        ReminderNotifier.confirm(c, "${inr(last.amount)} · " + last.note.ifBlank { last.merchant }.ifBlank { last.category })
                    }
                }
            }
            // Type just the amount in the notification; the head defaults to what you last used on this account.
            // MUTABLE PendingIntent (needed by RemoteInput): extras can be overwritten by the sender,
            // so derive account/pending ID from the DB and only accept matching values.
            Extras.ACTION_AMOUNT -> {
                val typed = RemoteInput.getResultsFromIntent(i)?.getCharSequence("amount")?.toString().orEmpty()
                val amt = typed.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                if (amt == null || !amt.isFinite() || amt <= 0.0 || amt > 1e12) { nm.cancel(nid); return }
                nm.cancel(nid)
                async(c) { db ->
                    val d = db.dao()
                    val pending = d.pendingById(nid.toLong()) ?: return@async
                    if ((acc != 0L && acc != pending.accountId) || (pid != 0L && pid != pending.id)) return@async
                    val account = pending.accountId.takeIf { it != 0L } ?: d.allAccounts().firstOrNull()?.id ?: return@async
                    val head = d.lastExpenseFor(account)?.category ?: "Other"
                    d.insert(Txn(type = "EXPENSE", amount = round2(amt), date = today(), category = head, accountId = account, createdAt = System.currentTimeMillis()))
                    d.deletePending(pending.id)
                    ReminderNotifier.confirm(c, "${inr(amt)} · $head. Open the app to add a title.")
                }
            }
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
