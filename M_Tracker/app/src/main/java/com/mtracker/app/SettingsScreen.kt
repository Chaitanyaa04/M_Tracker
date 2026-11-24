package com.mtracker.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, onMsg: (String) -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(page != "home") { page = "home" }
    val back = { page = "home" }
    Box(modifier.fillMaxSize()) {
        when (page) {
            "home" -> SettingsHome { page = it }
            "appearance" -> AppearancePage(vm, back)
            "security" -> SecurityPage(vm, onMsg, back)
            "reminders" -> RemindersPage(vm, back) { page = "help" }
            "habits" -> HabitsPage(vm, back)
            "categories" -> CategoriesPage(vm, back)
            "recurring" -> RecurringPage(vm, back)
            "data" -> DataPage(vm, onMsg, back)
            "help" -> HelpPage(back)
            else -> AboutPage(back)
        }
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(24.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cap("‹ Back", color = L.muted)
                Spacer(Modifier.width(14.dp))
                Cap(title, color = L.text)
            }
            Hairline()
        }
        content()
    }
}

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(24.dp, 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = L.text, fontSize = 16.sp)
                Spacer(Modifier.height(2.dp))
                Text(sub, color = L.muted, fontSize = 12.sp)
            }
            Text("›", color = L.muted, fontSize = 22.sp)
        }
        Hairline()
    }
}

@Composable
private fun SettingsHome(go: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Cap("Personalise", Modifier.padding(24.dp, 20.dp, 24.dp, 4.dp)) }
        item { NavRow("Appearance", "Theme, balances, haptics") { go("appearance") } }
        item { NavRow("Security & privacy", "App lock, hide in recents") { go("security") } }
        item { Cap("Capture", Modifier.padding(24.dp, 24.dp, 24.dp, 4.dp)) }
        item { NavRow("Reminders & detection", "Payment apps, delay, permissions") { go("reminders") } }
        item { NavRow("Daily habits", "Evening check-in, quick-add shortcut") { go("habits") } }
        item { NavRow("Categories", "Add or remove your categories") { go("categories") } }
        item { NavRow("Repeating transactions", "Rent, subscriptions, SIPs") { go("recurring") } }
        item { Cap("Your data", Modifier.padding(24.dp, 24.dp, 24.dp, 4.dp)) }
        item { NavRow("Backup, export & import", "Auto-backup folder, CSV, restore") { go("data") } }
        item { Cap("Support", Modifier.padding(24.dp, 24.dp, 24.dp, 4.dp)) }
        item { NavRow("Install & permission help", "Play Protect, “restricted setting”, usage access") { go("help") } }
        item { NavRow("About M_Tracker", "Version, privacy promise") { go("about") } }
    }
}

@Composable
private fun AppearancePage(vm: MainViewModel, onBack: () -> Unit) {
    val p = vm.prefs
    val theme by p.theme.collectAsState()
    val hide by p.hideBalances.collectAsState()
    val haptics by p.haptics.collectAsState()
    SubPage("Appearance", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("OLED Black", theme == "OLED") { p.setTheme("OLED") }
                    Chip("Editorial White", theme == "PAPER") { p.setTheme("PAPER") }
                    Chip("System", theme == "SYSTEM") { p.setTheme("SYSTEM") }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Hide balances (shows ••••)", hide) { p.setHide(it) }
                SwitchRow("Haptic feedback on keypad", haptics) { p.setHaptics(it) }
            }
        }
    }
}

@Composable
private fun SecurityPage(vm: MainViewModel, onMsg: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val lock by p.lockEnabled.collectAsState()
    val secure by p.secure.collectAsState()
    val canAuth = remember {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }
    SubPage("Security & privacy", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow("App lock (fingerprint, face or phone PIN)", lock) {
                    if (it && !canAuth) onMsg("Set a screen lock in Android settings first") else p.setLock(it)
                }
                SwitchRow("Hide in recents & block screenshots", secure) { p.setSecure(it) }
                Spacer(Modifier.height(8.dp))
                Cap("Privacy promise")
                Text("M_Tracker declares no INTERNET permission, so Android itself prevents it from sending anything off your device. There are no analytics, ads, accounts or servers. You can verify this under Settings → Apps → M_Tracker → Permissions.", color = L.muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun RemindersPage(vm: MainViewModel, onBack: () -> Unit, onHelp: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val mode by p.mode.collectAsState()
    val delay by p.delaySec.collectAsState()
    val sel by p.apps.collectAsState()
    val accs by vm.accounts.collectAsState()
    var query by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { loadLaunchables(ctx) } }
    val usageOk = remember(tick) { Perms.usage(ctx) }
    val listenerOk = remember(tick) { Perms.listener(ctx) }
    val shown = remember(installed, query) { installed.filter { query.isBlank() || it.first.contains(query, true) } }
    val accOptions = listOf("No account" to 0L) + accs.map { it.name to it.id }

    SubPage("Reminders & detection", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Detection mode")
                if (BuildConfig.AUTOFILL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("App-Launch Timer", mode == "A") { p.setMode("A"); MonitorController.sync(ctx) }
                        Chip("Notification Auto-Fill", mode == "B") { p.setMode("B"); MonitorController.sync(ctx) }
                    }
                }
                if (mode == "A") {
                    Text("Zero text reading. Only notices which selected app opened, then asks after your delay.", color = L.muted, fontSize = 12.sp)
                    if (usageOk) Cap("Usage access granted", color = L.green)
                    else {
                        MonoButton("Grant usage access", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
                        Text("Greyed out or “restricted”? Tap here:", color = L.muted, fontSize = 12.sp)
                        MonoButton("Fix it: step-by-step help", onHelp, filled = false)
                    }
                } else {
                    Text("Reads ₹ amount and merchant from notifications of selected apps, on this device only. Needs debit keywords; duplicates within 60 s are ignored.", color = L.muted, fontSize = 12.sp)
                    if (listenerOk) Cap("Notification access granted", color = L.green)
                    else MonoButton("Grant notification access", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                }
                if (!BuildConfig.AUTOFILL) Text("This standard build contains no notification-reading code (safer, and avoids Play Protect warnings).", color = L.muted, fontSize = 12.sp)
            }
            Hairline()
            if (mode == "A") {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cap("Ask me after opening an app")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("10s" to 10, "30s" to 30, "1m" to 60, "2m" to 120, "5m" to 300).forEach { (l, s) -> Chip(l, delay == s) { p.setDelay(s) } }
                    }
                }
                Hairline()
            }
            Column(Modifier.padding(24.dp, 20.dp, 24.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Monitored payment apps")
                Field("Search apps", query, { query = it })
            }
        }
        items(shown, key = { it.second }) { (label, pkg) ->
            val checked = sel.containsKey(pkg)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked, { on -> p.setApp(pkg, if (on) defaultAccount(label, accs) else null); MonitorController.sync(ctx) },
                    colors = CheckboxDefaults.colors(checkedColor = L.accent, checkmarkColor = L.onAccent, uncheckedColor = L.muted)
                )
                Text(label, Modifier.weight(1f), color = L.text, fontSize = 14.sp, maxLines = 1)
                if (checked) {
                    val id = sel[pkg] ?: 0L
                    Picker("", accOptions.firstOrNull { it.second == id }?.first ?: "No account", accOptions, { p.setApp(pkg, it) }, Modifier.width(140.dp))
                }
            }
        }
    }
}

@Composable
private fun HabitsPage(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val hour by p.checkinHour.collectAsState()
    val quick by p.quickAdd.collectAsState()
    SubPage("Daily habits", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Evening check-in")
                Text("One nudge if you haven’t logged anything, or a payment prompt is still waiting. Includes a one-tap “No spending today”.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Off" to -1, "8 PM" to 20, "9 PM" to 21, "10 PM" to 22).forEach { (l, h) ->
                        Chip(l, hour == h) { p.setCheckin(h); CheckinScheduler.sync(ctx) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Quick-add notification (always in your shade)", quick) { p.setQuickAdd(it); QuickAdd.sync(ctx) }
                Spacer(Modifier.height(8.dp))
                Cap("Shortcuts")
                Text("Long-press the M_Tracker icon on your home screen for “Add expense” and “Add income”. Drag a shortcut out to make its own icon.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun CategoriesPage(vm: MainViewModel, onBack: () -> Unit) {
    val cats by vm.categories.collectAsState()
    var newCat by remember { mutableStateOf("") }
    SubPage("Categories", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("New category", newCat, { newCat = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("Add expense", { vm.addCategory(newCat, "EXPENSE"); newCat = "" })
                    MonoButton("Add income", { vm.addCategory(newCat, "INCOME"); newCat = "" }, filled = false)
                }
                Text("Deleting a category never changes past transactions.", color = L.muted, fontSize = 12.sp)
            }
            Hairline()
        }
        items(cats, key = { it.kind + it.name }) { c ->
            Column {
                Row(Modifier.fillMaxWidth().padding(24.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = L.text, fontSize = 15.sp)
                        Cap(c.kind, color = L.muted)
                    }
                    Cap("Delete", Modifier.clickable { vm.deleteCategory(c.name, c.kind) }.padding(8.dp), color = L.red)
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun RecurringPage(vm: MainViewModel, onBack: () -> Unit) {
    val recs by vm.recurring.collectAsState()
    val accs by vm.accounts.collectAsState()
    val names = remember(accs) { accs.associate { it.id to it.name } }
    SubPage("Repeating transactions", onBack) {
        if (recs.isEmpty()) item {
            Text("Nothing repeating. When adding a transaction, open “More details” → Repeat.", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        }
        items(recs, key = { "r${it.id}" }) { r ->
            Column {
                Row(Modifier.fillMaxWidth().padding(24.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.merchant.ifBlank { r.category }, color = L.text, fontSize = 15.sp)
                        Text("${inr(r.amount)} · ${r.freq.lowercase()} · ${names[r.accountId] ?: "?"} · next ${dayLabel(r.nextDue)}", color = L.muted, fontSize = 12.sp)
                    }
                    Cap("Stop", Modifier.clickable { vm.deleteRecurring(r.id) }.padding(8.dp), color = L.red)
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun DataPage(vm: MainViewModel, onMsg: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val auto by p.autoBackup.collectAsState()
    val folder by p.backupUri.collectAsState()
    val last by p.lastBackup.collectAsState()
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u -> if (u != null) vm.exportCsv(u, onMsg) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importCsv(u, onMsg) }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) restoreUri = u }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            p.setBackupUri(u.toString()); BackupScheduler.sync(ctx); onMsg("Backup folder set")
        }
    }
    val lastText = if (last == 0L) "Never" else Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH))

    SubPage("Backup, export & import", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Backup (everything)")
                Text("This app has no cloud, so a backup is your safety net if you lose or reset your phone. Pick a folder (for example one synced by Drive or Syncthing) and M_Tracker saves a full backup there.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text(if (folder.isBlank()) "Folder: not set" else "Folder: set · last backup $lastText", color = L.text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton(if (folder.isBlank()) "Choose folder" else "Change folder", { tree.launch(null) }, filled = false)
                    MonoButton("Back up now", { vm.backupNow(onMsg) })
                }
                SwitchRow("Automatic weekly backup", auto) { p.setAutoBackup(it); BackupScheduler.sync(ctx) }
                MonoButton("Restore from backup file", { restorer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, filled = false)
            }
            Hairline()
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Spreadsheet (CSV)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("Export CSV", { export.launch("m_tracker-${LocalDate.now()}.csv") })
                    MonoButton("Import CSV", { importer.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }, filled = false)
                }
                Text("Files go through Android’s file picker. No storage permission, no network.", color = L.muted, fontSize = 12.sp)
            }
        }
    }
    restoreUri?.let { u ->
        AlertDialog(
            onDismissRequest = { restoreUri = null }, containerColor = L.surface,
            title = { Cap("Restore backup?", color = L.text) },
            text = { Text("This replaces everything currently in M_Tracker with the contents of the backup.", color = L.muted, fontSize = 14.sp) },
            confirmButton = { TextButton({ vm.restoreBackup(u, onMsg); restoreUri = null }) { Text("Restore", color = L.red) } },
            dismissButton = { TextButton({ restoreUri = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Num("$n.", size = 14.sp, color = L.muted, modifier = Modifier.width(24.dp))
        Text(text, color = L.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun HelpPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    SubPage("Install & permission help", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Cap("Usage access is greyed out / “restricted”", color = L.text)
                Text("Android blocks sensitive permissions for apps installed outside the Play Store. It’s a one-time unlock:", color = L.muted, fontSize = 13.sp, lineHeight = 19.sp)
                Step(1, "Tap “Open App info” below.")
                Step(2, "Tap the ⋮ menu at the top-right.")
                Step(3, "Choose “Allow restricted settings” and confirm with your PIN or fingerprint.")
                Step(4, "Come back and tap “Open usage access”, then switch M_Tracker on.")
                Text("If ⋮ doesn’t show the option, tap the usage-access toggle once (it shows the block message), then check App info again.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("Open App info", { openAppInfo(ctx) })
                    MonoButton("Open usage access", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, filled = false)
                }
                Spacer(Modifier.height(16.dp))
                Cap("Play Protect warning", color = L.text)
                Text("Play Protect is cautious about new apps from outside the Play Store, especially ones that read notifications. This standard build contains no notification-reading code and no internet access.", color = L.muted, fontSize = 13.sp, lineHeight = 19.sp)
                Step(1, "Install from your file manager (or adb), not directly from a browser or chat app.")
                Step(2, "If asked, tap “Scan app” / “Send for security check”. Once Google has seen the app, the prompt stops.")
                Step(3, "Keep installing updates signed with the same key, so the app stays recognised.")
                Text("Don’t turn Play Protect off permanently. Review each prompt and only proceed for an app you built yourself.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    SubPage("About", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("₹", fontSize = 56.sp, color = L.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
                Text("M_Tracker", color = L.text, fontSize = 22.sp)
                Num("Version ${BuildConfig.VERSION_NAME}" + if (BuildConfig.AUTOFILL) " · auto-fill build" else "", size = 13.sp, color = L.muted)
                Spacer(Modifier.height(8.dp))
                Text("A private, offline expense tracker. Your data lives only in a local database on this device. No internet permission, no analytics, no ads, no account.", color = L.muted, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}
