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
        putExtra(Extras.TOKEN, AppPrefs.get(c).intentToken)
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
        nm.createNotificationChannel(
            NotificationChannel(CH, "Payment reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Instant detection status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECKIN, "Daily check-in", NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATE, "Updates & confirmations", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    /** One prompt per quiet period (10 s to 10 min). No amount or merchant is read from anywhere. */
    fun show(c: Context, pkg: String, label: String, accountId: Long) {
        val app = c.applicationContext
        scope.launch {
            val p = AppPrefs.get(app)
            val now = System.currentTimeMillis()
            if (now - p.lastPrompt(pkg) < p.cooldownMs()) return@launch
            p.setPrompt(pkg, now)
            val pid = AppDatabase.get(app).dao().insertPending(
                Pending(time = now, pkg = pkg, label = label, accountId = accountId)
            )
            post(app, pid, pkg, label, accountId)
        }
    }

    @SuppressLint("MissingPermission")
    fun summary(c: Context, count: Int) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val pub = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Arthak")
            .setContentText("Payment reminder available")
            .build()
        nm.notify(4, NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle(if (count == 1) "1 payment visit to log" else "$count payment visits to log")
            .setContentText("Tap to review. Mark mistakes as wrong call.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
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

    /** Failure notice for the daily encrypted backup worker. No financial data is shown. */
    @SuppressLint("MissingPermission")
    fun backupFailed(c: Context) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(7, NotificationCompat.Builder(c, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Backup did not finish")
            .setContentText("Check the backup folder or passphrase in Settings → Backup & export.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(activityPi(c, 8004, null) { putExtra(Extras.OPEN, "data") })
            .build())
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, pid: Long, pkg: String, label: String, accountId: Long) {
        ensureChannels(c)
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val nid = pid.toInt()
        val appLocked = AppPrefs.get(c).lockEnabled.value

        // Opening Expense / Income only creates an empty draft. No ledger write happens from the notification.
        fun draft(type: String, rc: Int) = activityPi(c, nid * 8 + rc, type) {
            putExtra(Extras.ACCOUNT, accountId)
            putExtra(Extras.NOTIF_ID, nid)
            putExtra(Extras.PENDING, pid)
        }
        val wrong = PendingIntent.getBroadcast(
            c, nid * 8 + 3,
            Intent(c, NotificationActionReceiver::class.java).setAction(Extras.ACTION_WRONG).setPackage(c.packageName)
                .putExtra(Extras.NOTIF_ID, nid).putExtra(Extras.PKG, pkg).putExtra(Extras.PENDING, pid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pub = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Arthak")
            .setContentText("Payment reminder — unlock to view")
            .build()

        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Did you pay via $label?")
            .setContentText(
                if (appLocked) "Open Arthak and unlock to log it."
                else "Choose expense, income or wrong call. You enter the details."
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(30 * 60_000L)   // it also stays in the app under "Unlogged payments"
            .setContentIntent(draft("EXPENSE", 0))

        if (!appLocked) {
            b.addAction(0, "Expense", draft("EXPENSE", 1))
            b.addAction(0, "Income", draft("INCOME", 2))
            b.addAction(0, "Wrong call", wrong)
        }
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
        val appLocked = AppPrefs.get(c).lockEnabled.value
        val title = if (pending > 0) "$pending payment(s) not logged yet" else "Nothing logged today"
        val pub = NotificationCompat.Builder(c, ReminderNotifier.CH_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Arthak")
            .setContentText("Daily check-in")
            .build()
        val b = NotificationCompat.Builder(c, ReminderNotifier.CH_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle(title)
            .setContentText("Takes 5 seconds. Add it before you forget.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
            .setAutoCancel(true)
            .setContentIntent(activityPi(c, 7001, if (pending > 0) null else "EXPENSE") { putExtra(Extras.NOTIF_ID, ID) })
            .addAction(0, "Add expense", activityPi(c, 7002, "EXPENSE") { putExtra(Extras.NOTIF_ID, ID) })
        if (pending == 0 && !appLocked) b.addAction(
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
