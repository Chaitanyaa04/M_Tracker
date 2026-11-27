package com.mtracker.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/** Mode B (opt-in). Reads notification text from the user's allowed payment apps, on-device only. */
class PaymentNotificationListener : NotificationListenerService() {
    private val seen = ConcurrentHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = AppPrefs.get(this)
        if (p.mode.value != "B") return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val accId = p.apps.value[pkg] ?: return
        val ex = sbn.notification.extras ?: return
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT)
        ).joinToString(" ")
        if (!DEBIT.containsMatchIn(text) || CREDIT.containsMatchIn(text)) return
        val amount = AMOUNT.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val merchant = MERCHANT.find(text)?.groupValues?.get(1)?.trim()

        val now = System.currentTimeMillis()
        val key = "$pkg|$amount"
        val prev = seen[key]
        if (prev != null && now - prev < 60_000) return   // 60 s duplicate debounce
        seen[key] = now
        seen.entries.removeIf { now - it.value > 120_000 }

        ReminderNotifier.show(applicationContext, pkg, Perms.label(this, pkg), accId, amount, merchant)
    }

    private companion object {
        val DEBIT = Regex("(?i)\\b(debited|debit|paid|payment of|sent|spent|purchase|withdrawn|txn of)\\b")
        val CREDIT = Regex("(?i)\\b(credited|received|refund(ed)?)\\b")
        val AMOUNT = Regex("(?i)(?:₹|rs\\.?|inr)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        val MERCHANT = Regex("(?i)\\b(?:to|at|@)\\s+([A-Za-z0-9][A-Za-z0-9 &._'-]{1,28}?)(?=\\s+(?:on|via|using|ref|upi|from|for|a/c|txn)\\b|[.,;]|$)")
    }
}
