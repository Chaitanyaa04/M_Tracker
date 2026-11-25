package com.mtracker.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

object Extras {
    const val TYPE = "x_type"
    const val ACCOUNT = "x_account"
    const val AMOUNT = "x_amount"
    const val MERCHANT = "x_merchant"
    const val NOTIF_ID = "x_nid"
    const val PKG = "x_pkg"
    const val PENDING = "x_pending"
    const val OPEN = "x_open"
    const val ACTION_NO_SPEND = "com.mtracker.app.NO_SPEND"
    const val ACTION_WRONG = "com.mtracker.app.WRONG_CALL"
}

/** Local settings (SharedPreferences). apps maps packageName -> default accountId (0 = none). */
class AppPrefs private constructor(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = MutableStateFlow(sp.getString("theme", "OLED") ?: "OLED")        // OLED | PAPER | SYSTEM
    val mode = MutableStateFlow(sp.getString("mode", "A") ?: "A")                // A = app launch, B = notification text
    val delaySec = MutableStateFlow(sp.getInt("delay", 30))
    val apps = MutableStateFlow(load())
    val checkinHour = MutableStateFlow(sp.getInt("checkin", 21))                   // -1 = off
    val quickAdd = MutableStateFlow(sp.getBoolean("quick", false))
    val hideBalances = MutableStateFlow(sp.getBoolean("hide", false))
    val onboarded = MutableStateFlow(sp.getBoolean("onboarded", false))
    val lockEnabled = MutableStateFlow(sp.getBoolean("lock", false))
    val secure = MutableStateFlow(sp.getBoolean("secure", false))
    val haptics = MutableStateFlow(sp.getBoolean("haptics", true))
    val autoBackup = MutableStateFlow(sp.getBoolean("autobackup", false))
    val backupUri = MutableStateFlow(sp.getString("backupuri", "") ?: "")
    val lastBackup = MutableStateFlow(sp.getLong("lastbackup", 0L))
    val liveDetect = MutableStateFlow(sp.getBoolean("live", false))          // instant detection needs a status notification
    val lastScan = MutableStateFlow(sp.getLong("lastscan", 0L))
    val updateCheck = MutableStateFlow(sp.getBoolean("updcheck", true))
    val updateRepo = MutableStateFlow(sp.getString("updrepo", "") ?: "")
    val latestTag = MutableStateFlow(sp.getString("latesttag", "") ?: "")
    val lastUpdateCheck = MutableStateFlow(sp.getLong("lastupd", 0L))
    val notifiedTag = MutableStateFlow(sp.getString("notifiedtag", "") ?: "")

    private fun load(): Map<String, Long> =
        (sp.getString("apps", "") ?: "").split(";").filter { it.contains("=") }
            .associate { val kv = it.split("="); kv[0] to (kv[1].toLongOrNull() ?: 0L) }

    fun setTheme(v: String) { theme.value = v; sp.edit().putString("theme", v).apply() }
    fun setMode(v: String) { mode.value = v; sp.edit().putString("mode", v).apply() }
    fun setDelay(v: Int) { delaySec.value = v; sp.edit().putInt("delay", v).apply() }
    fun setCheckin(v: Int) { checkinHour.value = v; sp.edit().putInt("checkin", v).apply() }
    fun setQuickAdd(v: Boolean) { quickAdd.value = v; sp.edit().putBoolean("quick", v).apply() }
    fun setHide(v: Boolean) { hideBalances.value = v; sp.edit().putBoolean("hide", v).apply() }
    fun setOnboarded(v: Boolean) { onboarded.value = v; sp.edit().putBoolean("onboarded", v).apply() }
    fun setLock(v: Boolean) { lockEnabled.value = v; sp.edit().putBoolean("lock", v).apply() }
    fun setSecure(v: Boolean) { secure.value = v; sp.edit().putBoolean("secure", v).apply() }
    fun setHaptics(v: Boolean) { haptics.value = v; sp.edit().putBoolean("haptics", v).apply() }
    fun setAutoBackup(v: Boolean) { autoBackup.value = v; sp.edit().putBoolean("autobackup", v).apply() }
    fun setBackupUri(v: String) { backupUri.value = v; sp.edit().putString("backupuri", v).apply() }
    fun setLastBackup(v: Long) { lastBackup.value = v; sp.edit().putLong("lastbackup", v).apply() }
    fun setLive(v: Boolean) { liveDetect.value = v; sp.edit().putBoolean("live", v).apply() }
    fun setLastScan(v: Long) { lastScan.value = v; sp.edit().putLong("lastscan", v).apply() }
    fun setUpdateCheck(v: Boolean) { updateCheck.value = v; sp.edit().putBoolean("updcheck", v).apply() }
    fun setUpdateRepo(v: String) { updateRepo.value = v.trim(); sp.edit().putString("updrepo", v.trim()).apply() }
    fun setLatestTag(v: String) { latestTag.value = v; sp.edit().putString("latesttag", v).apply() }
    fun setLastUpdateCheck(v: Long) { lastUpdateCheck.value = v; sp.edit().putLong("lastupd", v).apply() }
    fun setNotifiedTag(v: String) { notifiedTag.value = v; sp.edit().putString("notifiedtag", v).apply() }
    /** Every "Wrong call" makes that app need a longer visit before we ask again. */
    fun wrongCount(pkg: String): Int = (sp.getString("wrong", "") ?: "").split(";").firstOrNull { it.startsWith("$pkg=") }?.substringAfter("=")?.toIntOrNull() ?: 0
    fun bumpWrong(pkg: String) {
        val m = (sp.getString("wrong", "") ?: "").split(";").filter { it.contains("=") }.associate { it.substringBefore("=") to (it.substringAfter("=").toIntOrNull() ?: 0) }.toMutableMap()
        m[pkg] = (m[pkg] ?: 0) + 1
        sp.edit().putString("wrong", m.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }
    fun minDwellMs(pkg: String): Long = minOf(90_000L, 15_000L + wrongCount(pkg) * 10_000L)
    fun setApp(pkg: String, accountId: Long?) {
        val m = apps.value.toMutableMap()
        if (accountId == null) m.remove(pkg) else m[pkg] = accountId
        apps.value = m
        sp.edit().putString("apps", m.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    companion object {
        @Volatile private var i: AppPrefs? = null
        fun get(c: Context): AppPrefs = i ?: synchronized(this) { i ?: AppPrefs(c).also { i = it } }
    }
}

/** Per-app cooldown so rapid open/minimise doesn't spam reminders. */
object Cooldown {
    private val m = ConcurrentHashMap<String, Long>()
    fun tryStart(pkg: String, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = m[pkg]
        if (last != null && now - last < windowMs) return false
        m[pkg] = now
        return true
    }
    fun reset(pkg: String) { m.remove(pkg) }
}

object Perms {
    fun usage(c: Context): Boolean {
        val ao = c.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun listener(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun label(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}

object MonitorController {
    /** Quiet mode (default): a ~15 min background scan, no persistent notification.
     *  Instant mode (opt-in): foreground service with a small status notification. */
    fun sync(c: Context) {
        val p = AppPrefs.get(c)
        val have = p.mode.value == "A" && p.apps.value.isNotEmpty() && Perms.usage(c)
        val live = have && p.liveDetect.value
        val i = Intent(c, AppLaunchMonitorService::class.java)
        try {
            if (live) ContextCompat.startForegroundService(c, i) else c.stopService(i)
        } catch (e: Exception) { /* retried on next app resume */ }
        UsageScanScheduler.sync(c, have && !p.liveDetect.value)
    }
}
