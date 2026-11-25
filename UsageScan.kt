package com.mtracker.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Quiet detection. Every ~15 minutes, look back at which payment apps you actually USED:
 *  - a visit must last long enough (learned per app: every "wrong call" raises the bar),
 *  - visits within 5 minutes of each other are one visit,
 *  - and ONE summary notification is sent, so repeated opens never spam you.
 * No foreground service, no always-on notification.
 */
class UsageScanWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    private class Sess(var start: Long, var lastPause: Long)

    override suspend fun doWork(): Result {
        val c = applicationContext
        val p = AppPrefs.get(c)
        val apps = p.apps.value
        if (apps.isEmpty() || !Perms.usage(c)) return Result.success()
        val now = System.currentTimeMillis()
        val since = p.lastScan.value.takeIf { it > 0 } ?: (now - 15 * 60_000)
        p.setLastScan(now)

        val usm = c.getSystemService(UsageStatsManager::class.java)
        val ev = usm.queryEvents(since, now)
        val e = UsageEvents.Event()
        val sessions = HashMap<String, Sess>()
        val visits = ArrayList<Triple<String, Long, Long>>()   // pkg, start, duration
        fun close(pkg: String, s: Sess) { visits.add(Triple(pkg, s.start, s.lastPause - s.start)) }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val pkg = e.packageName
            if (pkg !in apps) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val s = sessions[pkg]
                    if (s != null && e.timeStamp - s.lastPause <= 5_000) s.lastPause = e.timeStamp
                    else { if (s != null) close(pkg, s); sessions[pkg] = Sess(e.timeStamp, e.timeStamp) }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> sessions[pkg]?.lastPause = e.timeStamp
            }
        }
        sessions.forEach { (pkg, s) -> if (s.lastPause <= s.start) s.lastPause = now; close(pkg, s) }

        val dao = AppDatabase.get(c).dao()
        var added = 0
        for ((pkg, start, dur) in visits.sortedBy { it.second }) {
            if (dur < p.minDwellMs(pkg)) continue                                  // a glance, not a payment
            if (dao.countRecent(pkg, start - 5 * 60_000) > 0) continue              // same visit / too soon after the last one
            dao.insertPending(Pending(time = start, pkg = pkg, label = Perms.label(c, pkg), accountId = apps[pkg] ?: 0L))
            added++
        }
        if (added > 0) ReminderNotifier.summary(c, dao.pendingCount())
        return Result.success()
    }
}

object UsageScanScheduler {
    fun sync(c: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (enabled) wm.enqueueUniquePeriodicWork("usage-scan", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<UsageScanWorker>(15, TimeUnit.MINUTES).build())
        else wm.cancelUniqueWork("usage-scan")
    }
}
