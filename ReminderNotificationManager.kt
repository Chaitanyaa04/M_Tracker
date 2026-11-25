package com.mtracker.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

fun activityPi(c: Context, rc: Int, type: String?, extra: Intent.() -> Unit = {}): PendingIntent {
    val i = Intent(c, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (type != null) putExtra(Extras.TYPE, type)
        extra()
    }
    return PendingIntent.getActivity(c, rc, i, PI_FLAGS)
}

object ReminderNotifier {
    const val CH = "reminders"
    const val CH_MONITOR = "monitor"
    const val CH_CHECKIN = "checkin"
    const val CH_UPDATE = "updates"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Payment reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "App-launch guard", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_CHECKIN, "Daily check-in", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_LOW))
    }

    /** Saves the prompt as an "unlogged payment" FIRST, so it can't be forgotten, then notifies. */
    fun show(c: Context, pkg: String, label: String, accountId: Long, amount: Double?, merchant: String?) {
        val app = c.applicationContext
        scope.launch {
            val dao = AppDatabase.get(app).dao()
            // Same app triggered again within 5 minutes? That's the same visit (or a wrong call): stay quiet.
            if (dao.countRecent(pkg, System.currentTimeMillis() - 5 * 60_000) > 0) return@launch
            val pid = dao.insertPending(
                Pending(time = System.currentTimeMillis(), pkg = pkg, label = label, accountId = accountId, amount = amount, merchant = merchant)
            )
            post(app, pid, pkg, label, accountId, amount, merchant)
        }
    }

    @SuppressLint("MissingPermission")
    fun summary(c: Context, count: Int) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(4, NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle(if (count == 1) "1 payment visit to log" else "$count payment visits to log")
            .setContentText("Tap to review. Dismiss any that were a mistake.")
            .setAutoCancel(true).setContentIntent(activityPi(c, 8001, null)).build())
    }

    @SuppressLint("MissingPermission")
    fun updateAvailable(c: Context, tag: String) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(5, NotificationCompat.Builder(c, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("M_Tracker $tag is ready")
            .setContentText("Tap to update. It takes a few seconds.")
            .setAutoCancel(true)
            .setContentIntent(activityPi(c, 8002, null) { putExtra(Extras.OPEN, "updates") }).build())
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, pid: Long, pkg: String, label: String, accountId: Long, amount: Double?, merchant: String?) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val nid = pid.toInt()

        fun act(type: String, rc: Int) = activityPi(c, nid * 4 + rc, type) {
            putExtra(Extras.ACCOUNT, accountId)
            putExtra(Extras.NOTIF_ID, nid)
            putExtra(Extras.PENDING, pid)
            if (amount != null) putExtra(Extras.AMOUNT, amount)
            if (merchant != null) putExtra(Extras.MERCHANT, merchant)
        }

        val wrong = PendingIntent.getBroadcast(
            c, nid * 4 + 2,
            Intent(c, NotificationActionReceiver::class.java).setAction(Extras.ACTION_WRONG)
                .putExtra(Extras.NOTIF_ID, nid).putExtra(Extras.PKG, pkg).putExtra(Extras.PENDING, pid),
            PI_FLAGS
        )

        val title = if (amount != null)
            "Log ${inr(amount)}" + (if (!merchant.isNullOrBlank()) " at $merchant" else "") + "?"
        else "Did you just make a transaction via $label?"

        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
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

/** Evening nudge: unlogged payments, or nothing logged today. */
object CheckinNotifier {
    const val ID = 3

    @SuppressLint("MissingPermission")
    fun show(c: Context, pending: Int) {
        ReminderNotifier.ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val title = if (pending > 0) "$pending payment(s) not logged yet" else "Nothing logged today"
        val b = NotificationCompat.Builder(c, ReminderNotifier.CH_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle(title)
            .setContentText("Takes 5 seconds. Add it before you forget.")
            .setAutoCancel(true)
            .setContentIntent(activityPi(c, 7001, if (pending > 0) null else "EXPENSE") { putExtra(Extras.NOTIF_ID, ID) })
            .addAction(0, "Add expense", activityPi(c, 7002, "EXPENSE") { putExtra(Extras.NOTIF_ID, ID) })
        if (pending == 0) b.addAction(
            0, "No spending today",
            PendingIntent.getBroadcast(
                c, 7003,
                Intent(c, NotificationActionReceiver::class.java).setAction(Extras.ACTION_NO_SPEND).putExtra(Extras.NOTIF_ID, ID),
                PI_FLAGS
            )
        )
        nm.notify(ID, b.build())
    }
}
