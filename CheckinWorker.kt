package com.mtracker.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class CheckinWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val db = AppDatabase.get(app)
        val d = db.dao()
        Repository(db).materializeDue()
        val t = today()
        val pending = d.pendingCount()
        val logged = d.countOn(t) > 0 || d.markOn(t) > 0
        if (pending > 0 || !logged) CheckinNotifier.show(app, pending)
        return Result.success()
    }
}

object CheckinScheduler {
    private const val NAME = "daily-checkin"

    fun sync(c: Context) {
        val wm = WorkManager.getInstance(c)
        val h = AppPrefs.get(c).checkinHour.value
        if (h < 0) { wm.cancelUniqueWork(NAME); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(h, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<CheckinWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
