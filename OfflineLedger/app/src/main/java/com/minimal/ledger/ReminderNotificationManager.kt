package com.minimal.ledger

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ReminderNotifier {
    const val CH = "reminders"
    const val CH_MONITOR = "monitor"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Payment reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "App-launch guard", NotificationManager.IMPORTANCE_MIN))
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context, pkg: String, label: String, accountId: Long, amount: Double?, merchant: String?) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val nid = pkg.hashCode()

        fun act(type: String, rc: Int): PendingIntent {
            val i = Intent(c, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Extras.TYPE, type)
                putExtra(Extras.ACCOUNT, accountId)
                putExtra(Extras.NOTIF_ID, nid)
                if (amount != null) putExtra(Extras.AMOUNT, amount)
                if (merchant != null) putExtra(Extras.MERCHANT, merchant)
            }
            return PendingIntent.getActivity(c, nid * 3 + rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val wrong = PendingIntent.getBroadcast(
            c, nid * 3 + 2,
            Intent(c, NotificationActionReceiver::class.java).setAction(Extras.ACTION_WRONG)
                .putExtra(Extras.NOTIF_ID, nid).putExtra(Extras.PKG, pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (amount != null)
            "Log ${inr(amount)}" + (if (!merchant.isNullOrBlank()) " at $merchant" else "") + "?"
        else "Did you just make a transaction via $label?"

        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(act("EXPENSE", 0))
            .addAction(0, "Expense", act("EXPENSE", 0))
            .addAction(0, "Income", act("INCOME", 1))
            .addAction(0, "Wrong Call", wrong)
            .build()
        nm.notify(nid, n)
    }
}
