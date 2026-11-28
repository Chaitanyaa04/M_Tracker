package com.mtracker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (vm.prefs.secure.value || vm.prefs.lockEnabled.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { App(vm, onAuth = { authenticate() }, onConfirm = { title, ok -> confirmSensitiveAction(title, ok) }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        val p = vm.prefs
        if (p.lockEnabled.value && p.onboarded.value) {
            vm.locked.value = true
            authenticate()
        }
    }

    /** Picks BIOMETRIC_STRONG | DEVICE_CREDENTIAL if available, falling back to DEVICE_CREDENTIAL alone. */
    private fun resolveAllowedAuthenticators(): Pair<Int, Int> {
        val bm = BiometricManager.from(this)
        val strongOrPin = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val statusStrong = bm.canAuthenticate(strongOrPin)
        if (statusStrong == BiometricManager.BIOMETRIC_SUCCESS) return strongOrPin to statusStrong
        val pinOnly = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val statusPin = bm.canAuthenticate(pinOnly)
        if (statusPin == BiometricManager.BIOMETRIC_SUCCESS) return pinOnly to statusPin
        return strongOrPin to statusStrong
    }

    private fun authenticate() {
        val (auth, status) = resolveAllowedAuthenticators()
        when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                vm.locked.value = true
                startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(this, "Set a screen lock (or biometrics) to unlock Arthak.", Toast.LENGTH_LONG).show()
                return
            }
            // Fail CLOSED on any authenticator error rather than unlocking without verification.
            else -> {
                vm.locked.value = true
                Toast.makeText(this, "Device authentication unavailable. Please try again.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.locked.value = false }
            // Fail closed: if the user cancels or auth fails, stay on the lock screen.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { vm.locked.value = true }
            override fun onAuthenticationFailed() { vm.locked.value = true }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Arthak").setAllowedAuthenticators(auth).build()
        )
    }

    /** Requires fresh biometric/PIN confirmation for sensitive actions when App Lock is enabled. */
    private fun confirmSensitiveAction(title: String, onConfirmed: () -> Unit) {
        if (!vm.prefs.lockEnabled.value) { onConfirmed(); return }
        val (auth, status) = resolveAllowedAuthenticators()
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Verify device lock settings before continuing.", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onConfirmed() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth).build()
        )
    }

    /** Reminder / shortcut / check-in actions arrive here as extras. Only trusted internal PendingIntents may supply privileged extras. */
    private fun handle(i: Intent?) {
        if (i == null) return
        val suppliedToken = i.getStringExtra(Extras.TOKEN)
        val trusted = suppliedToken != null &&
            java.security.MessageDigest.isEqual(
                suppliedToken.toByteArray(Charsets.UTF_8),
                vm.prefs.intentToken.toByteArray(Charsets.UTF_8)
            )
        i.removeExtra(Extras.TOKEN)

        if (trusted) {
            i.getStringExtra(Extras.OPEN)?.takeIf { it in ALLOWED_OPEN_PAGES }?.let {
                vm.settingsTarget.value = it
            }
            i.removeExtra(Extras.OPEN)
            val type = i.getStringExtra(Extras.TYPE) ?: return
            if (type !in ALLOWED_INTERNAL_TYPES) { i.removeExtra(Extras.TYPE); return }
            val nid = i.getIntExtra(Extras.NOTIF_ID, -1)
            if (nid >= 0) NotificationManagerCompat.from(this).cancel(nid)
            vm.fromReminder(type, i.getLongExtra(Extras.ACCOUNT, 0L), i.getLongExtra(Extras.PENDING, 0L))
            i.removeExtra(Extras.TYPE)
        } else {
            // Untrusted/external intents (including static launcher shortcuts) may only open an empty EXPENSE or INCOME draft.
            val type = i.getStringExtra(Extras.TYPE)
            if (type in ALLOWED_SHORTCUT_TYPES) {
                vm.fromReminder(type!!, 0L, 0L)
            }
            i.removeExtra(Extras.TYPE)
            i.removeExtra(Extras.OPEN)
        }
    }

    companion object {
        private val ALLOWED_OPEN_PAGES = setOf("home", "updates", "security", "data", "reminders")
        private val ALLOWED_INTERNAL_TYPES = setOf("EXPENSE", "INCOME", "TRANSFER")
        private val ALLOWED_SHORTCUT_TYPES = setOf("EXPENSE", "INCOME")
    }
}

private val TABS = listOf("Ledger", "Accounts", "Plan", "Insights")
private val TAB_ICONS = listOf(Icons.Rounded.ReceiptLong, Icons.Rounded.AccountBalanceWallet, Icons.Rounded.Flag, Icons.Rounded.Insights)

@Composable
fun App(vm: MainViewModel, onAuth: () -> Unit, onConfirm: (String, () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val theme by vm.prefs.theme.collectAsState()
    val onboarded by vm.prefs.onboarded.collectAsState()
    val locked by vm.locked.collectAsState()
    val secure by vm.prefs.secure.collectAsState()
    val haptics by vm.prefs.haptics.collectAsState()
    val win = (ctx as Activity).window
    LaunchedEffect(secure, locked) {
        if (secure || locked) win.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LedgerTheme(theme) {
        CompositionLocalProvider(LocalHaptics provides haptics) {
            Box(Modifier.fillMaxSize().background(L.bg)) {
                if (!onboarded) Onboarding(vm) {
                    vm.prefs.setOnboarded(true)
                    MonitorController.sync(ctx); CheckinScheduler.sync(ctx)
                } else Shell(vm, onConfirm)
                if (locked && onboarded) LockScreen(onAuth)
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (selected) L.text else L.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Cap(label, color = if (selected) L.text else L.muted)
    }
}

/** Floating pill: four tabs and a raised + in the middle. */
@Composable
private fun FloatingNav(tab: Int, showSettings: Boolean, onTab: (Int) -> Unit, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(L.surface).border(1.dp, L.border, shape).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(TABS[0], TAB_ICONS[0], !showSettings && tab == 0, Modifier.weight(1f)) { onTab(0) }
            NavItem(TABS[1], TAB_ICONS[1], !showSettings && tab == 1, Modifier.weight(1f)) { onTab(1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(19.dp)).background(L.accent).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, "Add transaction", tint = L.onAccent, modifier = Modifier.size(30.dp)) }
            }
            NavItem(TABS[2], TAB_ICONS[2], !showSettings && tab == 2, Modifier.weight(1f)) { onTab(2) }
            NavItem(TABS[3], TAB_ICONS[3], !showSettings && tab == 3, Modifier.weight(1f)) { onTab(3) }
        }
    }
}

@Composable
private fun Shell(vm: MainViewModel, onConfirm: (String, () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val apps by vm.prefs.apps.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsStart by remember { mutableStateOf("home") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val headFirst by vm.headFirst.collectAsState()
    val accs by vm.accounts.collectAsState()
    val heads by vm.visibleCategories.collectAsState()
    val usage by vm.usage.collectAsState()
    val iconMap by vm.iconMap.collectAsState()
    val recents by vm.recents.collectAsState()
    val merchants by vm.merchantNames.collectAsState()
    val people by vm.persons.collectAsState()
    val groups by vm.groups.collectAsState()
    val groupMembers by vm.groupMembers.collectAsState()
    val trips by vm.trips.collectAsState()
    val latest by vm.prefs.latestTag.collectAsState()
    val target by vm.settingsTarget.collectAsState()
    LaunchedEffect(target) { target?.let { settingsStart = it; showSettings = true; vm.settingsTarget.value = null } }

    fun deleteTxn(tx: Txn) {
        vm.delete(tx)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Transaction deleted", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(tx)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        MonitorController.sync(ctx); CheckinScheduler.sync(ctx); BackupScheduler.sync(ctx); UpdateScheduler.sync(ctx)
        vm.materialize()
        NotificationManagerCompat.from(ctx).cancel(2)   // remove the old always-on quick-add notification if it's still there
        val pr = vm.prefs
        if (pr.updateCheck.value && System.currentTimeMillis() - pr.lastUpdateCheck.value > 12 * 3_600_000L) Updater.check(ctx)
    }
    LaunchedEffect(apps) { MonitorController.sync(ctx) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler(showSettings) { showSettings = false }

    val status = remember(tick) { DetectStatus("Payment prompts", Perms.usage(ctx)) }

    CompositionLocalProvider(LocalHeadIcons provides iconMap) {
        Scaffold(
            containerColor = L.bg,
            snackbarHost = {
                SnackbarHost(snackbar) {
                    Snackbar(it, shape = RoundedCornerShape(12.dp), containerColor = L.accent, contentColor = L.onAccent, actionContentColor = L.onAccent)
                }
            },
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(L.bg).statusBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("₹", color = L.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(10.dp))
                        Cap(if (showSettings) "Settings" else TABS[tab], color = L.text)
                    }
                    Icon(
                        if (showSettings) Icons.Rounded.Close else Icons.Rounded.Tune, "Settings", tint = L.text,
                        modifier = Modifier.size(24.dp).clickable { showSettings = !showSettings }
                    )
                }
            },
            bottomBar = { FloatingNav(tab, showSettings, { tab = it; showSettings = false; settingsStart = "home" }) { vm.newDraft() } }
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
              if (!showSettings && latest.isNotBlank() && Updater.isNewer(latest, BuildConfig.VERSION_NAME)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp).clip(RoundedCornerShape(16.dp)).background(L.accent)
                        .clickable { settingsStart = "updates"; showSettings = true }.padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Update available", color = L.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("$latest is ready to install", color = L.onAccent.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Cap("Update", color = L.onAccent)
                }
              }
              Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(if (showSettings) 99 else tab, label = "screen") { t ->
                    when (t) {
                        99 -> SettingsScreen(vm, { m -> scope.launch { snackbar.showSnackbar(m) } }, startPage = settingsStart, onConfirm = onConfirm)
                        0 -> LedgerScreen(
                            vm, status,
                            onStatus = {
                                if (!status.ok) ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                else showSettings = true
                            },
                            onDelete = { tx -> deleteTxn(tx) },
                            onPending = { p, a -> if (a == "DISMISS") vm.dismissPending(p) else vm.fromPending(p, a) }
                        )
                        1 -> AccountsScreen(vm, onTransfer = { vm.newDraft("TRANSFER") })
                        2 -> PlanScreen(vm)
                        else -> InsightsScreen(vm)
                    }
                }
              }
            }
        }

        headFirst?.let { first ->
            val kind = if (first.type == "INCOME") "INCOME" else "EXPENSE"
            HeadsSheet(
                heads = heads, initialKind = kind, selected = null, usage = usage,
                likely = { vm.likely(it) }, showKindSwitch = false,
                label = "New entry", hint = "Pick a head first — amount and details come next.",
                onPick = { name, k -> vm.pickHead(name, k) },
                onDismiss = { vm.cancelHeadFirst() }
            )
        } ?: draft?.let { d ->
            AddEditTransactionSheet(
                d, accs, heads, usage, likely = { vm.likely(it) }, recents = recents, merchantNames = merchants,
                lookup = { vm.lookup(it) },
                people = people, groups = groups, groupMembers = groupMembers, trips = trips,
                lastTrip = { vm.lastTrip() }, places = { vm.places() }, routes = { vm.usualRoutes() },
                onAddPerson = { n, cb -> vm.addPerson(n, cb) },
                onAddHead = { n, k, ic -> vm.addHead(n, k, ic) },
                onDelete = { tx -> deleteTxn(tx) },
                onDismiss = { vm.draft.value = null },
                onSave = { t, r -> vm.save(t, r, d.pendingId) }
            )
        }
    }
}
