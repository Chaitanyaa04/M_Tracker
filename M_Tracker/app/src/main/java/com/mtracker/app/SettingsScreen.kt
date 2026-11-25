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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Today
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
fun SettingsScreen(vm: MainViewModel, onMsg: (String) -> Unit, modifier: Modifier = Modifier, startPage: String = "home") {
    var page by rememberSaveable(startPage) { mutableStateOf(startPage) }
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
            "updates" -> UpdatesPage(vm, back)
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
private fun NavRow(title: String, sub: String, icon: ImageVector, last: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(L.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = L.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = L.text, fontSize = 15.sp)
                Text(sub, color = L.muted, fontSize = 12.sp)
            }
            Text("›", color = L.muted, fontSize = 22.sp)
        }
        if (!last) Hairline(Modifier.padding(start = 68.dp))
    }
}

@Composable
private fun SettingsHome(go: (String) -> Unit) {
    val latest by AppPrefs.get(androidx.compose.ui.platform.LocalContext.current).latestTag.collectAsState()
    val hasUpdate = latest.isNotBlank() && Updater.isNewer(latest, BuildConfig.VERSION_NAME)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item { Cap("Make it yours", Modifier.padding(24.dp, 12.dp, 24.dp, 8.dp)) }
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                NavRow("Appearance", "Theme, balances, haptics", Icons.Rounded.Palette, false) { go("appearance") }
                NavRow("Security & privacy", "App lock, hide in recents", Icons.Rounded.Lock, true) { go("security") }
            }
        }
        item { Cap("Capture", Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp)) }
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                NavRow("Payment reminders", "Which apps, how quietly", Icons.Rounded.Notifications, false) { go("reminders") }
                NavRow("Daily habits", "Evening check-in", Icons.Rounded.Today, false) { go("habits") }
                NavRow("Heads & icons", "Your expense and income heads", Icons.Rounded.Label, false) { go("categories") }
                NavRow("Repeating entries", "Rent, subscriptions, SIPs", Icons.Rounded.Repeat, true) { go("recurring") }
            }
        }
        item { Cap("Your data", Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp)) }
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                NavRow("Backup & export", "Auto-backup, CSV, restore", Icons.Rounded.Backup, true) { go("data") }
            }
        }
        item { Cap("App", Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp)) }
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                NavRow("App updates", if (hasUpdate) "$latest is available" else "Version ${BuildConfig.VERSION_NAME}", Icons.Rounded.SystemUpdate, false) { go("updates") }
                NavRow("Install & permission help", "Play Protect, usage access", Icons.Rounded.Help, false) { go("help") }
                NavRow("About M_Tracker", "Privacy promise", Icons.Rounded.Info, true) { go("about") }
            }
        }
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
                Text("Your transactions, accounts and people never leave this phone. There are no analytics, ads, accounts or servers. The internet is used for one thing only: checking and downloading app updates from your GitHub releases (you can switch that off under App updates).", color = L.muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindersPage(vm: MainViewModel, onBack: () -> Unit, onHelp: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val mode by p.mode.collectAsState()
    val live by p.liveDetect.collectAsState()
    val delay by p.delaySec.collectAsState()
    val sel by p.apps.collectAsState()
    val accs by vm.accounts.collectAsState()
    var installed by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var browse by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { loadLaunchables(ctx) } }
    val usageOk = remember(tick) { Perms.usage(ctx) }
    val listenerOk = remember(tick) { Perms.listener(ctx) }
    val suggested = remember(installed, sel) { installed.filter { looksLikePayment(it.first, it.second) && it.second !in sel }.take(6) }
    val accOptions = listOf("No account" to 0L) + accs.map { it.name to it.id }

    SubPage("Payment reminders", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (BuildConfig.AUTOFILL) {
                    Cap("Detection")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Which app I opened", mode == "A") { p.setMode("A"); MonitorController.sync(ctx) }
                        Chip("Read notifications", mode == "B") { p.setMode("B"); MonitorController.sync(ctx) }
                    }
                }
                if (mode == "A") {
                    Cap("How should it notice?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Quietly", !live) { p.setLive(false); MonitorController.sync(ctx) }
                        Chip("Instantly", live) { p.setLive(true); MonitorController.sync(ctx) }
                    }
                    Text(
                        if (!live) "Checks in the background every ~15 minutes and sends one tidy summary. Nothing sits in your notification shade. Short glances at an app are ignored."
                        else "Notices right away and asks after a short delay. Android requires a small status notification while this is on.",
                        color = L.muted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    if (live) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("10s" to 10, "30s" to 30, "1m" to 60, "2m" to 120).forEach { (l, s) -> Chip(l, delay == s) { p.setDelay(s) } }
                    }
                    if (usageOk) Cap("Usage access granted", color = L.green)
                    else {
                        MonoButton("Allow usage access", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
                        Text("Greyed out? That’s Android’s sideload lock.", color = L.muted, fontSize = 12.sp)
                        MonoButton("Show me how to fix it", onHelp, filled = false)
                    }
                } else {
                    Text("Reads the amount and merchant from notifications of the apps below, on this device only.", color = L.muted, fontSize = 12.sp)
                    if (listenerOk) Cap("Notification access granted", color = L.green)
                    else MonoButton("Allow notification access", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                }
                Text("If an app fires again within 5 minutes you won’t be asked twice. Each “wrong call” makes that app a little less eager next time.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        item { Cap("Apps I pay with", Modifier.padding(24.dp, 4.dp, 24.dp, 8.dp)) }
        item {
            if (sel.isEmpty()) Text("None yet. Add the apps you pay with below.", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp))
            else Panel(Modifier.padding(horizontal = 16.dp)) {
                sel.keys.toList().forEachIndexed { i, pkg ->
                    val label = remember(pkg) { Perms.label(ctx, pkg) }
                    val id = sel[pkg] ?: 0L
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, 42.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, color = L.text, fontSize = 15.sp, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Pays from ", color = L.muted, fontSize = 12.sp)
                                Picker("", accOptions.firstOrNull { it.second == id }?.first ?: "No account", accOptions, { p.setApp(pkg, it) }, Modifier.width(150.dp))
                            }
                        }
                        Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Remove", tint = L.muted,
                            modifier = Modifier.size(22.dp).clickable { p.setApp(pkg, null); MonitorController.sync(ctx) })
                    }
                    if (i < sel.size - 1) Hairline(Modifier.padding(start = 72.dp))
                }
            }
        }
        if (suggested.isNotEmpty()) {
            item { Cap("Found on your phone", Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp)) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    suggested.forEach { (label, pkg) ->
                        Column(
                            Modifier.width(92.dp).clip(RoundedCornerShape(18.dp)).clickable {
                                p.setApp(pkg, defaultAccount(label, accs)); MonitorController.sync(ctx)
                            }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AppIcon(pkg, 52.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(label, color = L.text, fontSize = 11.sp, maxLines = 1)
                            Cap("+ Add", color = L.muted)
                        }
                    }
                }
            }
        }
        item { Box(Modifier.padding(24.dp)) { MonoButton("Browse all apps", { browse = true }, filled = false) } }
    }

    if (browse) {
        var q by remember { mutableStateOf("") }
        val shown = installed.filter { q.isBlank() || it.first.contains(q, true) }
        ModalBottomSheet(onDismissRequest = { browse = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = L.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) { Cap("Add an app", color = L.text); Spacer(Modifier.height(8.dp)); Field("Search", q, { q = it }) }
            LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                lazyItems(shown, key = { it.second }) { (label, pkg) ->
                    val on = sel.containsKey(pkg)
                    Row(Modifier.fillMaxWidth().clickable {
                        p.setApp(pkg, if (on) null else defaultAccount(label, accs)); MonitorController.sync(ctx)
                    }.padding(24.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, 38.dp); Spacer(Modifier.width(14.dp))
                        Text(label, color = L.text, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = L.accent, checkmarkColor = L.onAccent, uncheckedColor = L.muted))
                    }
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
                Spacer(Modifier.height(8.dp))
                Cap("Shortcuts")
                Text("Long-press the M_Tracker icon on your home screen for “Add expense” and “Add income”. Drag one out to keep it on your home screen. Nothing sits in your notification shade.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun CategoriesPage(vm: MainViewModel, onBack: () -> Unit) {
    val cats by vm.categories.collectAsState()
    var creating by remember { mutableStateOf(false) }
    SubPage("Heads (categories)", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MonoButton("+ New head", { creating = true })
                Text("Heads are the buckets you file money into. Deleting one never changes past transactions.", color = L.muted, fontSize = 12.sp)
            }
            Hairline()
        }
        items(cats, key = { it.kind + it.name }) { c ->
            Column {
                Row(Modifier.fillMaxWidth().padding(24.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeadTile(c.name, 40.dp, stored = c.icon.ifEmpty { null })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = L.text, fontSize = 15.sp)
                        Cap(c.kind, color = L.muted)
                    }
                    Cap("Delete", Modifier.clickable { vm.deleteCategory(c.name, c.kind) }.padding(8.dp), color = L.red)
                }
                Hairline(Modifier.padding(start = 78.dp))
            }
        }
    }
    if (creating) NewHeadDialog("EXPENSE", { n, k, ic -> vm.addHead(n, k, ic); creating = false }, { creating = false })
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
                Text("A private expense tracker. Your data lives only in a local database on this device. No analytics, no ads, no account. The internet is used only to fetch app updates.", color = L.muted, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun UpdatesPage(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val st by Updater.state.collectAsState()
    val auto by p.updateCheck.collectAsState()
    val repoSaved by p.updateRepo.collectAsState()
    var repo by remember { mutableStateOf(repoSaved.ifBlank { BuildConfig.GITHUB_REPO }) }
    LaunchedEffect(Unit) { Updater.check(ctx, manual = true) }
    SubPage("App updates", onBack) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Cap("Installed")
                        Num("Version ${BuildConfig.VERSION_NAME}", size = 22.sp, weight = FontWeight.Light)
                        when (val s = st) {
                            is UpdState.Checking -> Text("Checking…", color = L.muted, fontSize = 13.sp)
                            is UpdState.UpToDate, is UpdState.Idle -> Text("You’re on the latest version.", color = L.green, fontSize = 13.sp)
                            is UpdState.Available -> {
                                Text("${s.info.tag} is available", color = L.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                if (s.info.notes.isNotBlank()) Text(s.info.notes.trim().take(420), color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
                                MonoButton("Update now", { Updater.downloadAndInstall(ctx, s.info) }, Modifier.fillMaxWidth())
                            }
                            is UpdState.Downloading -> {
                                Text("Downloading… ${s.pct}%", color = L.text, fontSize = 14.sp)
                                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(L.border)) {
                                    Box(Modifier.fillMaxWidth(s.pct / 100f).fillMaxHeight().background(L.text))
                                }
                            }
                            is UpdState.Installing -> Text("Installing… confirm if Android asks.", color = L.text, fontSize = 14.sp)
                            is UpdState.Error -> Text(s.msg, color = L.red, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
                MonoButton("Check now", { Updater.check(ctx, manual = true) }, filled = false)
                SwitchRow("Check for updates daily and tell me", auto) { p.setUpdateCheck(it); UpdateScheduler.sync(ctx) }
                Field("GitHub repository (owner/name)", repo, { repo = it; p.setUpdateRepo(it) })
                Text("New versions come from the Releases page of that repository. Android asks you to confirm each install once. The repository must be public for the app to see it.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
