package com.mtracker.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val KNOWN_PAYMENT_PKGS = setOf(
    "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
    "in.org.npci.upiapp", "in.amazon.mShop.android.shopping", "com.canarabank.mobility"
)
private val PAY_WORDS = listOf(
    "pay", "bank", "upi", "phonepe", "paytm", "slice", "canara", "sbi", "hdfc", "icici", "axis", "kotak",
    "cred", "bhim", "mobikwik", "freecharge", "jupiter", "groww", "zerodha", "imobile", "yono", "navi", "fi money"
)

@Suppress("DEPRECATION")
fun loadLaunchables(c: Context): List<Pair<String, String>> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .filter { it.second != c.packageName }
        .distinctBy { it.second }
        .sortedWith(compareByDescending<Pair<String, String>> { it.second in KNOWN_PAYMENT_PKGS }.thenBy { it.first.lowercase() })
}

fun looksLikePayment(label: String, pkg: String) =
    pkg in KNOWN_PAYMENT_PKGS || PAY_WORDS.any { label.contains(it, true) || pkg.contains(it, true) }

fun defaultAccount(label: String, accs: List<Account>): Long {
    val w = label.trim().split(" ").first()
    return if (w.length < 3) 0L else accs.firstOrNull { it.name.contains(w, ignoreCase = true) }?.id ?: 0L
}

fun openAppInfo(c: Context) {
    c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(L.bg).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("₹", fontSize = 72.sp, fontWeight = FontWeight.Light, color = L.text)
            Cap("M_Tracker is locked")
            MonoButton("Unlock", onUnlock)
        }
    }
}

@Composable
fun Onboarding(vm: MainViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val accs by vm.accounts.collectAsState()
    val bal by vm.balances.collectAsState()
    val apps by vm.prefs.apps.collectAsState()
    val hour by vm.prefs.checkinHour.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val usageOk = remember(tick) { Perms.usage(ctx) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var suggested by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    LaunchedEffect(Unit) {
        suggested = withContext(Dispatchers.IO) { loadLaunchables(ctx).filter { looksLikePayment(it.first, it.second) }.take(8) }
    }
    val last = 3

    Column(Modifier.fillMaxSize().background(L.bg).statusBarsPadding().navigationBarsPadding().padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(last + 1) { i -> Box(Modifier.weight(1f).height(2.dp).background(if (i <= step) L.text else L.border)) }
        }
        Spacer(Modifier.height(36.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AnimatedContent(step, label = "onboarding") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s) {
                        0 -> {
                            Text("₹", fontSize = 72.sp, fontWeight = FontWeight.Light, color = L.text)
                            Text("Every rupee, tracked on your phone only.", color = L.text, fontSize = 28.sp, fontWeight = FontWeight.Light, lineHeight = 34.sp)
                            Text("M_Tracker has no internet permission, no account and no cloud. Your money data never leaves this device.", color = L.muted, fontSize = 15.sp, lineHeight = 22.sp)
                            Text("It’s built so logging takes five seconds: a quick keypad, one-tap repeats, reminders after you pay, and a nightly check-in so nothing slips.", color = L.muted, fontSize = 15.sp, lineHeight = 22.sp)
                        }
                        1 -> {
                            Cap("Step 2 · Accounts", color = L.text)
                            Text("Where does your money live?", color = L.text, fontSize = 24.sp, fontWeight = FontWeight.Light)
                            Text("Tap an account to set its balance. Add your bank, wallets and credit cards.", color = L.muted, fontSize = 14.sp)
                            Hairline()
                            accs.forEach { a ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { editing = a }.padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(a.name, color = L.text, fontSize = 15.sp)
                                    Num(inrS(bal[a.id] ?: 0.0))
                                }
                                Hairline()
                            }
                            MonoButton("+ Add account", { adding = true }, filled = false)
                        }
                        2 -> {
                            Cap("Step 3 · Payment reminders", color = L.text)
                            Text("Get asked right after you pay.", color = L.text, fontSize = 24.sp, fontWeight = FontWeight.Light)
                            Text("M_Tracker notices only which payment app you opened (never what’s on screen), waits a few seconds, then asks “Did you just pay?”. Optional, you can skip it.", color = L.muted, fontSize = 14.sp, lineHeight = 21.sp)
                            if (usageOk) Cap("Usage access granted", color = L.green)
                            else {
                                MonoButton("Grant usage access", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
                                Text("Toggle greyed out? Android restricts sideloaded apps. Open App info → ⋮ → “Allow restricted settings”, then try again.", color = L.muted, fontSize = 12.sp, lineHeight = 18.sp)
                                MonoButton("Open App info", { openAppInfo(ctx) }, filled = false)
                            }
                            if (Build.VERSION.SDK_INT >= 33) MonoButton("Allow notifications", { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }, filled = false)
                            Cap("Apps to watch")
                            if (suggested.isEmpty()) Text("No payment apps detected yet. You can add some later in Settings.", color = L.muted, fontSize = 13.sp)
                            suggested.forEach { (label, pkg) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        apps.containsKey(pkg), { on -> vm.prefs.setApp(pkg, if (on) defaultAccount(label, accs) else null) },
                                        colors = CheckboxDefaults.colors(checkedColor = L.accent, checkmarkColor = L.onAccent, uncheckedColor = L.muted)
                                    )
                                    Text(label, color = L.text, fontSize = 15.sp)
                                }
                            }
                        }
                        else -> {
                            Cap("Step 4 · Daily check-in", color = L.text)
                            Text("A nightly nudge keeps the habit.", color = L.text, fontSize = 24.sp, fontWeight = FontWeight.Light)
                            Text("If you haven’t logged anything, or a payment prompt is waiting, you’ll get one reminder, with a one-tap “No spending today”.", color = L.muted, fontSize = 14.sp, lineHeight = 21.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Off" to -1, "8 PM" to 20, "9 PM" to 21, "10 PM" to 22).forEach { (l, h) ->
                                    Chip(l, hour == h) { vm.prefs.setCheckin(h) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) MonoButton("Back", { step-- }, filled = false) else Spacer(Modifier.width(1.dp))
            MonoButton(if (step == last) "Start tracking" else "Continue", { if (step == last) onDone() else step++ })
        }
    }

    if (adding) AccountDialog(null, { adding = false }, { vm.saveAccount(it); adding = false }, {})
    editing?.let { a ->
        AccountDialog(a, { editing = null }, { vm.saveAccount(it); editing = null }, { vm.deleteAccount(a.id); editing = null })
    }
}
