package com.mtracker.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
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
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Instant detection status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_CHECKIN, "Daily check-in", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATE, "Updates & confirmations", NotificationManager.IMPORTANCE_LOW))
    }

    /**
     * One prompt per quiet period (you choose 10 s to 10 min). A repeat trigger inside it is ignored even if you
     * already dismissed the first one. A prompt that carries its own amount is a different payment, so it always passes.
     */
    fun show(c: Context, pkg: String, label: String, accountId: Long, amount: Double?, merchant: String?) {
        val app = c.applicationContext
        scope.launch {
            val p = AppPrefs.get(app)
            val now = System.currentTimeMillis()
            if (amount == null && now - p.lastPrompt(pkg) < p.cooldownMs()) return@launch
            p.setPrompt(pkg, now)
            val dao = AppDatabase.get(app).dao()
            val pid = dao.insertPending(Pending(time = now, pkg = pkg, label = label, accountId = accountId, amount = amount, merchant = merchant))
            val last = if (accountId != 0L) dao.lastExpenseFor(accountId) else null
            post(app, pid, pkg, label, accountId, amount, merchant, last)
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
            .setContentTitle("Arthak $tag is ready")
            .setContentText("Tap to update. It takes a few seconds.")
            .setAutoCancel(true)
            .setContentIntent(activityPi(c, 8002, null) { putExtra(Extras.OPEN, "updates") }).build())
    }

    /** Brief "logged" receipt after a one-tap action; removes itself after a few seconds. */
    @SuppressLint("MissingPermission")
    fun confirm(c: Context, text: String) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(6, NotificationCompat.Builder(c, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Logged")
            .setContentText(text)
            .setAutoCancel(true).setTimeoutAfter(6_000)
            .setContentIntent(activityPi(c, 8003, null)).build())
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, pid: Long, pkg: String, label: String, accountId: Long, amount: Double?, merchant: String?, last: Txn?) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val nid = pid.toInt()

        fun act(type: String, rc: Int) = activityPi(c, nid * 8 + rc, type) {
            putExtra(Extras.ACCOUNT, accountId)
            putExtra(Extras.NOTIF_ID, nid)
            putExtra(Extras.PENDING, pid)
            if (amount != null) putExtra(Extras.AMOUNT, amount)
            if (merchant != null) putExtra(Extras.MERCHANT, merchant)
        }
        fun bc(action: String, rc: Int, mutable: Boolean = false) = PendingIntent.getBroadcast(
            c, nid * 8 + rc,
            Intent(c, NotificationActionReceiver::class.java).setAction(action)
                .putExtra(Extras.NOTIF_ID, nid).putExtra(Extras.PKG, pkg).putExtra(Extras.PENDING, pid).putExtra(Extras.ACCOUNT, accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        )

        // Type the amount right in the notification: no need to open the app.
        val enter = NotificationCompat.Action.Builder(0, "Enter amount", bc(Extras.ACTION_AMOUNT, 3, mutable = true))
            .addRemoteInput(RemoteInput.Builder("amount").setLabel("Amount, e.g. 250").build())
            .setAllowGeneratedReplies(false).build()

        val title = if (amount != null)
            "Log ${inr(amount)}" + (if (!merchant.isNullOrBlank()) " at $merchant" else "") + "?"
        else "Did you just pay via $label?"
        val lastTitle = last?.let { it.note.ifBlank { it.merchant }.ifBlank { it.category } }

        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle(title)
            .setContentText(if (last != null) "Last time: $lastTitle · ${inr(last.amount)}" else label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(30 * 60_000L)   // it stays in the app under "Unlogged payments"
            .setContentIntent(act("EXPENSE", 0))
        if (last != null) b.addAction(0, "Same again · ${inr(last.amount)}", bc(Extras.ACTION_SAME, 1))
        else b.addAction(0, "Expense", act("EXPENSE", 0))
        b.addAction(enter)
        b.addAction(0, "Wrong call", bc(Extras.ACTION_WRONG, 2))
        nm.notify(nid, b.build())
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
