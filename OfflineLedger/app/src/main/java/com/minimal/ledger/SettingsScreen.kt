package com.minimal.ledger

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val KNOWN_PAYMENT_PKGS = setOf(
    "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
    "in.org.npci.upiapp", "in.amazon.mShop.android.shopping", "com.canarabank.mobility"
)

@Suppress("DEPRECATION")
private fun loadLaunchables(c: Context): List<Pair<String, String>> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .filter { it.second != c.packageName }
        .distinctBy { it.second }
        .sortedWith(compareByDescending<Pair<String, String>> { it.second in KNOWN_PAYMENT_PKGS }.thenBy { it.first.lowercase() })
}

private fun defaultAccount(label: String, accs: List<Account>): Long {
    val w = label.trim().split(" ").first()
    return if (w.length < 3) 0L else accs.firstOrNull { it.name.contains(w, ignoreCase = true) }?.id ?: 0L
}

@Composable
fun SettingsScreen(vm: MainViewModel, onMsg: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = vm.prefs
    val theme by p.theme.collectAsState()
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
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u -> if (u != null) vm.exportCsv(u, onMsg) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importCsv(u, onMsg) }

    val shown = remember(installed, query) { installed.filter { query.isBlank() || it.first.contains(query, true) } }
    val accOptions = listOf("No account" to 0L) + accs.map { it.name to it.id }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("OLED Black", theme == "OLED") { p.setTheme("OLED") }
                    Chip("Editorial White", theme == "PAPER") { p.setTheme("PAPER") }
                    Chip("System", theme == "SYSTEM") { p.setTheme("SYSTEM") }
                }
            }
            Hairline()
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Detection mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Safe App-Launch Timer", mode == "A") { p.setMode("A"); MonitorController.sync(ctx) }
                    Chip("Notification Auto-Fill", mode == "B") { p.setMode("B"); MonitorController.sync(ctx) }
                }
                if (mode == "A") {
                    Text("Zero text reading. Only notices which selected app was opened, then asks after your delay.", color = L.muted, fontSize = 12.sp)
                    if (usageOk) Cap("Usage access granted", color = L.green)
                    else MonoButton("Grant usage access", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
                } else {
                    Text("Reads ₹ amount and merchant from notifications of the selected apps, on this device only. Needs debit keywords; duplicates within 60 s are ignored.", color = L.muted, fontSize = 12.sp)
                    if (listenerOk) Cap("Notification access granted", color = L.green)
                    else MonoButton("Grant notification access", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                }
            }
            Hairline()
            if (mode == "A") {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cap("Reminder delay after opening an app")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("10s" to 10, "30s" to 30, "1m" to 60, "2m" to 120, "5m" to 300).forEach { (l, s) ->
                            Chip(l, delay == s) { p.setDelay(s) }
                        }
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
                    checked, { on ->
                        p.setApp(pkg, if (on) defaultAccount(label, accs) else null)
                        MonitorController.sync(ctx)
                    },
                    colors = CheckboxDefaults.colors(checkedColor = L.accent, checkmarkColor = L.onAccent, uncheckedColor = L.muted)
                )
                Text(label, Modifier.weight(1f), color = L.text, fontSize = 14.sp, maxLines = 1)
                if (checked) {
                    val id = sel[pkg] ?: 0L
                    Picker("", accOptions.firstOrNull { it.second == id }?.first ?: "No account", accOptions,
                        { p.setApp(pkg, it) }, Modifier.width(140.dp))
                }
            }
        }
        item {
            Hairline(Modifier.padding(top = 16.dp))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cap("Data portability")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("Export CSV", { export.launch("ledger-${LocalDate.now()}.csv") })
                    MonoButton("Import CSV", { importer.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }, filled = false)
                }
                Text("Files are read and written through Android's file picker. No storage permission, no network.", color = L.muted, fontSize = 12.sp)
            }
        }
    }
}
