package com.mtracker.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Extras {
    const val TYPE = "x_type"
    const val ACCOUNT = "x_account"
    const val AMOUNT = "x_amount"
    const val MERCHANT = "x_merchant"
    const val NOTIF_ID = "x_nid"
    const val PKG = "x_pkg"
    const val PENDING = "x_pending"
    const val OPEN = "x_open"
    const val TOKEN = "x_token"
    const val ACTION_SAME = "com.mtracker.app.SAME_AGAIN"
    const val ACTION_AMOUNT = "com.mtracker.app.ENTER_AMOUNT"
    const val ACTION_NO_SPEND = "com.mtracker.app.NO_SPEND"
    const val ACTION_WRONG = "com.mtracker.app.WRONG_CALL"
}

/** Reads UTF-8 text from an InputStream with a strict byte cap to prevent OOM on oversized files/responses. */
fun readTextBounded(input: InputStream, maxBytes: Int = 10 * 1024 * 1024): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw IllegalArgumentException("File exceeds maximum allowed size (${maxBytes / (1024 * 1024)} MB).")
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

/** Hardware/OS-backed AES-GCM wrapper for storing the backup passphrase without saving it in plaintext. */
private object LocalKeyWrap {
    private const val ALIAS = "arthak_backup_pass_wrap"
    private const val STORE = "AndroidKeyStore"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(ivB64: String, ctB64: String): String? = runCatching {
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}

/** Local settings (SharedPreferences). apps maps packageName -> default accountId (0 = none). */
class AppPrefs private constructor(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = MutableStateFlow(sp.getString("theme", "OLED") ?: "OLED")        // OLED | PAPER | SYSTEM
    val mode = MutableStateFlow(sp.getString("mode", "A") ?: "A")                // A = app launch, B = notification text
    val delaySec = MutableStateFlow(sp.getInt("delay", 30))
    val apps = MutableStateFlow(load())
    val checkinHour = MutableStateFlow(sp.getInt("checkin", 21))                   // -1 = off
    val hideBalances = MutableStateFlow(sp.getBoolean("hide", false))
    val onboarded = MutableStateFlow(sp.getBoolean("onboarded", false))
    val lockEnabled = MutableStateFlow(sp.getBoolean("lock", false))
    val secure = MutableStateFlow(sp.getBoolean("secure", true))
    val haptics = MutableStateFlow(sp.getBoolean("haptics", true))
    val autoBackup = MutableStateFlow(sp.getBoolean("autobackup", false))
    val backupUri = MutableStateFlow(sp.getString("backupuri", "") ?: "")
    val hasBackupPassphrase = MutableStateFlow(!sp.getString("bp_ct", "").isNullOrBlank())
    val lastBackup = MutableStateFlow(sp.getLong("lastbackup", 0L))
    val backupFiles = MutableStateFlow(loadBackupFiles())
    val cooldownSec = MutableStateFlow(sp.getInt("cooldown", 120))            // quiet period after a prompt: 10 s to 10 min
    val liveDetect = MutableStateFlow(sp.getBoolean("live", false))          // instant detection needs a status notification
    val lastScan = MutableStateFlow(sp.getLong("lastscan", 0L))
    val updateCheck = MutableStateFlow(sp.getBoolean("updcheck", true))
    val updateRepo = MutableStateFlow(sp.getString("updrepo", "") ?: "")
    val latestTag = MutableStateFlow(sp.getString("latesttag", "") ?: "")
    val lastUpdateCheck = MutableStateFlow(sp.getLong("lastupd", 0L))
    val notifiedTag = MutableStateFlow(sp.getString("notifiedtag", "") ?: "")

    /** Per-install random secret used to verify internal notification PendingIntents arriving at exported MainActivity. */
    @Volatile private var intentTokenCache: String? = null
    val intentToken: String
        get() = intentTokenCache ?: synchronized(this) {
            intentTokenCache ?: loadOrCreateIntentToken().also { intentTokenCache = it }
        }

    private fun loadOrCreateIntentToken(): String {
        val iv = sp.getString("it_iv", null)
        val ct = sp.getString("it_ct", null)
        if (iv != null && ct != null) {
            LocalKeyWrap.decrypt(iv, ct)?.takeIf { it.length >= 32 }?.let { return it }
        }
        val legacy = sp.getString("intent_token", null)?.takeIf { it.length >= 32 }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tok = legacy ?: Base64.encodeToString(fresh, Base64.NO_WRAP)
        storeIntentToken(tok)
        return tok
    }

    private fun storeIntentToken(tok: String) {
        val (iv, ct) = LocalKeyWrap.encrypt(tok)
        sp.edit().putString("it_iv", iv).putString("it_ct", ct).remove("intent_token").apply()
    }

    /** Rotating invalidates every previously issued privileged PendingIntent. */
    fun rotateIntentToken() {
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tok = Base64.encodeToString(fresh, Base64.NO_WRAP)
        storeIntentToken(tok)
        intentTokenCache = tok
    }

    private fun load(): Map<String, Long> =
        (sp.getString("apps", "") ?: "").split(";").filter { it.contains("=") }
            .associate { val kv = it.split("="); kv[0] to (kv[1].toLongOrNull() ?: 0L) }

    fun setTheme(v: String) { theme.value = v; sp.edit().putString("theme", v).apply() }
    fun setMode(v: String) { mode.value = v; sp.edit().putString("mode", v).apply() }
    fun setDelay(v: Int) { delaySec.value = v; sp.edit().putInt("delay", v).apply() }
    fun setCheckin(v: Int) { checkinHour.value = v; sp.edit().putInt("checkin", v).apply() }
    fun setHide(v: Boolean) { hideBalances.value = v; sp.edit().putBoolean("hide", v).apply() }
    fun setOnboarded(v: Boolean) { onboarded.value = v; sp.edit().putBoolean("onboarded", v).apply() }
    fun setLock(v: Boolean) {
        if (v != lockEnabled.value) rotateIntentToken()
        lockEnabled.value = v
        sp.edit().putBoolean("lock", v).apply()
    }
    fun setSecure(v: Boolean) { secure.value = v; sp.edit().putBoolean("secure", v).apply() }
    fun setHaptics(v: Boolean) { haptics.value = v; sp.edit().putBoolean("haptics", v).apply() }
    fun setAutoBackup(v: Boolean) { autoBackup.value = v; sp.edit().putBoolean("autobackup", v).apply() }
    fun setBackupUri(v: String) { backupUri.value = v; sp.edit().putString("backupuri", v).apply() }

    private fun loadBackupFiles(): Set<String> =
        (sp.getString("backupfiles", "") ?: "").split(';').filter { it.startsWith("content://") }.toSet()

    /** Track only files this app wrote, so pruning never touches unrelated user files. */
    fun addBackupFile(uri: String) {
        backupFiles.value = (backupFiles.value + uri).toList().takeLast(8).toSet()
        sp.edit().putString("backupfiles", backupFiles.value.joinToString(separator = ";")).apply()
    }

    /** Returns tracked URIs beyond the newest 8 and forgets them. */
    fun pruneBackupFiles(): Set<String> {
        val keep = backupFiles.value.toList().takeLast(8).toSet()
        val removed = backupFiles.value - keep
        backupFiles.value = keep
        sp.edit().putString("backupfiles", keep.joinToString(separator = ";")).apply()
        return removed
    }

    fun setBackupPassphrase(passphrase: String): Boolean {
        val trimmed = passphrase.trim()
        if (trimmed.isEmpty()) {
            sp.edit().remove("bp_iv").remove("bp_ct").apply()
            hasBackupPassphrase.value = false
            return true
        }
        return runCatching {
            val (iv, ct) = LocalKeyWrap.encrypt(trimmed)
            sp.edit().putString("bp_iv", iv).putString("bp_ct", ct).apply()
            hasBackupPassphrase.value = true
            true
        }.getOrDefault(false)
    }
    fun getBackupPassphrase(): String? {
        val iv = sp.getString("bp_iv", null) ?: return null
        val ct = sp.getString("bp_ct", null) ?: return null
        return LocalKeyWrap.decrypt(iv, ct)?.takeIf { it.isNotBlank() }
    }
    fun setLastBackup(v: Long) { lastBackup.value = v; sp.edit().putLong("lastbackup", v).apply() }
    fun setCooldown(v: Int) { cooldownSec.value = v; sp.edit().putInt("cooldown", v).apply() }
    fun cooldownMs(): Long = cooldownSec.value * 1000L
    fun lastPrompt(pkg: String): Long = sp.getLong("lp_$pkg", 0L)
    fun setPrompt(pkg: String, t: Long) { sp.edit().putLong("lp_$pkg", t).apply() }
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
