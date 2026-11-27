package com.mtracker.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(val tag: String, val notes: String, val apkUrl: String, val size: Long)

sealed class UpdState {
    object Idle : UpdState()
    object Checking : UpdState()
    object UpToDate : UpdState()
    data class Available(val info: UpdateInfo) : UpdState()
    data class Downloading(val pct: Int) : UpdState()
    object Installing : UpdState()
    data class Error(val msg: String) : UpdState()
}

/**
 * Checks the GitHub Releases of your repo and installs the new APK through Android's own installer.
 * The ONLY network use in the app. Nothing about you or your data is ever sent.
 */
object Updater {
    val state = MutableStateFlow<UpdState>(UpdState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MAX_API_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    /** Only strict owner/name strings (without path traversal segments) are accepted. */
    private val REPO_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")

    fun isValidRepo(r: String): Boolean {
        val t = r.trim()
        if (!REPO_REGEX.matches(t) || t.contains("..")) return false
        val parts = t.split("/")
        return parts.size == 2 && parts.none { it == "." || it == ".." || it.endsWith(".") }
    }

    fun repo(c: Context): String {
        val r = AppPrefs.get(c).updateRepo.value.ifBlank { BuildConfig.GITHUB_REPO }.trim()
        return if (isValidRepo(r)) r else BuildConfig.GITHUB_REPO.trim()
    }

    private fun nums(v: String) = v.trim().removePrefix("v").removePrefix("V").split(".", "-").mapNotNull { it.toIntOrNull() }
    fun isNewer(tag: String, current: String): Boolean {
        val a = nums(tag); val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun fetchLatest(c: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val r = repo(c)
        if (!isValidRepo(r) || r.startsWith("YOUR_USER")) return@withContext null
        val conn = URL("https://api.github.com/repos/$r/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false   // never follow redirects off api.github.com
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Arthak")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub answered ${conn.responseCode}. Is the repository public and does it have a release?")
            }
            val body = conn.inputStream.use { readTextBounded(it, MAX_API_BYTES) }
            val o = JSONObject(body)
            val assets = o.optJSONArray("assets") ?: return@withContext null
            var url = ""; var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    val dl = a.optString("browser_download_url")
                    // Only ever download from GitHub's own hosts, over HTTPS.
                    if (!isTrustedDownloadUrl(dl)) continue
                    url = dl; size = a.optLong("size"); break
                }
            }
            if (url.isEmpty()) null else UpdateInfo(o.optString("tag_name"), o.optString("body"), url, size)
        } finally {
            conn.disconnect()
        }
    }

    private val TRUSTED_HOSTS = setOf(
        "github.com",
        "codeload.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "raw.githubusercontent.com"
    )

    internal fun isTrustedDownloadUrl(u: String): Boolean = runCatching {
        val uri = URI(u)
        uri.scheme == "https" && uri.userInfo == null && uri.host?.lowercase() in TRUSTED_HOSTS
    }.getOrDefault(false)

    /** Opens an HTTPS connection to a trusted GitHub host, validating every redirect hop against the allowlist. */
    private fun openTrustedDownload(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            if (!isTrustedDownloadUrl(currentUrl)) throw SecurityException("Update source is not a trusted GitHub host.")
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Arthak")
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect missing Location header.")
                currentUrl = URI(currentUrl).resolve(loc).toString()
            } else if (code == HttpURLConnection.HTTP_OK) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("Download failed with HTTP $code.")
            }
        }
        throw IllegalStateException("Too many redirects while downloading update.")
    }

    /** Checks and updates app state + the cached "latest version". Safe to call often. */
    fun check(c: Context, manual: Boolean = false) {
        val app = c.applicationContext
        scope.launch {
            val p = AppPrefs.get(app)
            if (manual) state.value = UpdState.Checking
            try {
                val info = fetchLatest(app)
                p.setLastUpdateCheck(System.currentTimeMillis())
                if (info != null && isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                    p.setLatestTag(info.tag); state.value = UpdState.Available(info)
                } else {
                    p.setLatestTag(""); state.value = if (manual) UpdState.UpToDate else UpdState.Idle
                }
            } catch (e: Exception) {
                if (manual) state.value = UpdState.Error(e.message ?: "Couldn't check for updates")
            }
        }
    }

    fun downloadAndInstall(c: Context, info: UpdateInfo) {
        val app = c.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            state.value = UpdState.Error("Allow “Install unknown apps” for Arthak, then tap Update again.")
            return
        }
        scope.launch {
            var file: File? = null
            var conn: HttpURLConnection? = null
            try {
                state.value = UpdState.Downloading(0)
                val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                // Use a fixed local filename so remote tag strings can never influence filesystem paths.
                file = File(dir, "update.apk")
                if (!isTrustedDownloadUrl(info.apkUrl)) {
                    state.value = UpdState.Error("Update source is not a trusted GitHub host.")
                    return@launch
                }
                conn = openTrustedDownload(info.apkUrl)
                val declared = conn.contentLengthLong
                if (declared > MAX_APK_BYTES) {
                    state.value = UpdState.Error("Update file is larger than allowed.")
                    return@launch
                }
                val total = declared.takeIf { it > 0 } ?: info.size
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            done += n
                            if (done > MAX_APK_BYTES) throw IllegalStateException("Update exceeds maximum allowed size.")
                            out.write(buf, 0, n)
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != last) { last = pct; state.value = UpdState.Downloading(pct) }
                            }
                        }
                    }
                }
                state.value = UpdState.Installing
                if (!signerMatchesInstalled(app, file)) {
                    state.value = UpdState.Error("This update does not match Arthak's package or signing key. Refusing to install.")
                    file.delete()
                    return@launch
                }
                install(app, file)
            } catch (e: Exception) {
                file?.delete()
                state.value = UpdState.Error("Download failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun certDigests(info: android.content.pm.PackageInfo?): Set<String>? {
        val signers = info?.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        return signers.map { s ->
            md.reset()
            md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * Strict package + certificate pinning: the downloaded APK must have the exact same
     * packageName and identical SHA-256 signing certificate set as the installed app.
     * Always fails CLOSED on any read error across all supported Android versions (API 30+).
     */
    private fun signerMatchesInstalled(c: Context, apk: File): Boolean {
        val pm = c.packageManager
        val currentInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(c.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        }.getOrNull() ?: return false

        val archiveInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        }.getOrNull() ?: return false

        if (archiveInfo.packageName != c.packageName) return false

        val currentCerts = certDigests(currentInfo) ?: return false
        val newCerts = certDigests(archiveInfo) ?: return false
        return currentCerts.isNotEmpty() && currentCerts == newCerts
    }

    private fun install(c: Context, file: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // USER_ACTION_REQUIRED (the default): Android always shows its confirmation screen before installing.
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { input -> s.openWrite("update", 0, file.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val cb = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallResultReceiver::class.java).setPackage(c.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(cb.intentSender)
        }
    }
}

/** Receives the installer's verdict; shows Android's confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = UpdState.Idle
            else -> Updater.state.value = UpdState.Error(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed")
        }
    }
}

class UpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val p = AppPrefs.get(applicationContext)
        if (!p.updateCheck.value) return Result.success()
        try {
            val info = Updater.fetchLatest(applicationContext)
            p.setLastUpdateCheck(System.currentTimeMillis())
            if (info != null && Updater.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                p.setLatestTag(info.tag)
                if (p.notifiedTag.value != info.tag) { ReminderNotifier.updateAvailable(applicationContext, info.tag); p.setNotifiedTag(info.tag) }
            } else p.setLatestTag("")
        } catch (e: Exception) { /* offline: try again tomorrow */ }
        return Result.success()
    }
}

object UpdateScheduler {
    fun sync(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (AppPrefs.get(c).updateCheck.value)
            wm.enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).build())
        else wm.cancelUniqueWork("update-check")
    }
}
