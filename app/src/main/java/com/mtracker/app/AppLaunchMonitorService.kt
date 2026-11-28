package com.mtracker.app

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/**
 * Looks ONLY at "which package came to the foreground" from UsageStatsManager.
 * No screen content, SMS, notification text or amount is ever read.
 */
class AppLaunchMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var last = System.currentTimeMillis()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        ReminderNotifier.ensureChannels(this)
        val n = NotificationCompat.Builder(this, ReminderNotifier.CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Instant detection on")
            .setContentText("Turn off in Settings → Reminders to remove this.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (job == null) {
            last = System.currentTimeMillis()
            job = scope.launch { while (isActive) { poll(); delay(1500) } }
        }
        return START_STICKY
    }

    private fun poll() {
        val p = AppPrefs.get(this)
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(last, now)
        last = now
        val e = UsageEvents.Event()
        val fg = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == fg) handle(e.packageName, p)
        }
    }

    private fun handle(pkg: String, p: AppPrefs) {
        val accId = p.apps.value[pkg] ?: return
        // Wrong calls raise the bar here too, just like in quiet mode.
        val delayMs = maxOf(p.delaySec.value * 1000L, p.minDwellMs(pkg))
        if (!Cooldown.tryStart(pkg, maxOf(p.cooldownMs(), delayMs + 5_000))) return
        scope.launch {
            delay(delayMs)
            val c = applicationContext
            ReminderNotifier.show(c, pkg, Perms.label(c, pkg), accId)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
