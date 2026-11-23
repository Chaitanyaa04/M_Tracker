package com.minimal.ledger

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
    const val ACTION_WRONG = "com.minimal.ledger.WRONG_CALL"
}

/** Local settings (SharedPreferences). apps maps packageName -> default accountId (0 = none). */
class AppPrefs private constructor(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = MutableStateFlow(sp.getString("theme", "OLED") ?: "OLED")        // OLED | PAPER | SYSTEM
    val mode = MutableStateFlow(sp.getString("mode", "A") ?: "A")                // A = app launch, B = notification text
    val delaySec = MutableStateFlow(sp.getInt("delay", 30))
    val apps = MutableStateFlow(load())

    private fun load(): Map<String, Long> =
        (sp.getString("apps", "") ?: "").split(";").filter { it.contains("=") }
            .associate { val kv = it.split("="); kv[0] to (kv[1].toLongOrNull() ?: 0L) }

    fun setTheme(v: String) { theme.value = v; sp.edit().putString("theme", v).apply() }
    fun setMode(v: String) { mode.value = v; sp.edit().putString("mode", v).apply() }
    fun setDelay(v: Int) { delaySec.value = v; sp.edit().putInt("delay", v).apply() }
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
    /** Starts/stops the app-launch foreground service to match settings and permissions. */
    fun sync(c: Context) {
        val p = AppPrefs.get(c)
        val want = p.mode.value == "A" && p.apps.value.isNotEmpty() && Perms.usage(c)
        val i = Intent(c, AppLaunchMonitorService::class.java)
        try {
            if (want) ContextCompat.startForegroundService(c, i) else c.stopService(i)
        } catch (e: Exception) { /* background start restrictions: retried on next app resume */ }
    }
}
