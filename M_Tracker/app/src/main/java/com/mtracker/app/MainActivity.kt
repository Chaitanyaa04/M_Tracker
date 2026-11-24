package com.mtracker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { App(vm) { authenticate() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        val p = vm.prefs
        if (p.lockEnabled.value && p.onboarded.value &&
            (vm.stoppedAt == 0L || SystemClock.elapsedRealtime() - vm.stoppedAt > 20_000)
        ) { vm.locked.value = true; authenticate() }
    }

    override fun onStop() {
        super.onStop()
        vm.stoppedAt = SystemClock.elapsedRealtime()
    }

    private fun authenticate() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.locked.value = false   // no screen lock set on the phone: nothing to authenticate with
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.locked.value = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock M_Tracker").setAllowedAuthenticators(auth).build()
        )
    }

    /** Reminder / shortcut / check-in actions arrive here as extras. */
    private fun handle(i: Intent?) {
        val type = i?.getStringExtra(Extras.TYPE) ?: return
        NotificationManagerCompat.from(this).cancel(i.getIntExtra(Extras.NOTIF_ID, -1))
        val amount = if (i.hasExtra(Extras.AMOUNT)) i.getDoubleExtra(Extras.AMOUNT, 0.0) else null
        vm.fromReminder(type, i.getLongExtra(Extras.ACCOUNT, 0L), amount, i.getStringExtra(Extras.MERCHANT), i.getLongExtra(Extras.PENDING, 0L))
        i.removeExtra(Extras.TYPE)
    }
}

private val TABS = listOf("Ledger", "Accounts", "Plan", "Insights")

@Composable
fun App(vm: MainViewModel, onAuth: () -> Unit) {
    val ctx = LocalContext.current
    val theme by vm.prefs.theme.collectAsState()
    val onboarded by vm.prefs.onboarded.collectAsState()
    val locked by vm.locked.collectAsState()
    val secure by vm.prefs.secure.collectAsState()
    val haptics by vm.prefs.haptics.collectAsState()
    val win = (ctx as Activity).window
    LaunchedEffect(secure) {
        if (secure) win.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LedgerTheme(theme) {
        CompositionLocalProvider(LocalHaptics provides haptics) {
            Box(Modifier.fillMaxSize().background(L.bg)) {
                if (!onboarded) Onboarding(vm) {
                    vm.prefs.setOnboarded(true)
                    MonitorController.sync(ctx); CheckinScheduler.sync(ctx)
                } else Shell(vm)
                if (locked && onboarded) LockScreen(onAuth)
            }
        }
    }
}

@Composable
private fun Shell(vm: MainViewModel) {
    val ctx = LocalContext.current
    val mode by vm.prefs.mode.collectAsState()
    val apps by vm.prefs.apps.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val accs by vm.accounts.collectAsState()
    val expenseCats by vm.expenseCats.collectAsState()
    val incomeCats by vm.incomeCats.collectAsState()
    val recents by vm.recents.collectAsState()
    val merchants by vm.merchantNames.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        MonitorController.sync(ctx); QuickAdd.sync(ctx); CheckinScheduler.sync(ctx); BackupScheduler.sync(ctx)
        vm.materialize()
    }
    LaunchedEffect(mode, apps) { MonitorController.sync(ctx) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler(showSettings) { showSettings = false }

    val status = remember(tick, mode) {
        if (mode == "A") DetectStatus("App-Launch Guard", Perms.usage(ctx)) else DetectStatus("Notification Parser", Perms.listener(ctx))
    }

    Scaffold(
        containerColor = L.bg,
        snackbarHost = {
            SnackbarHost(snackbar) {
                Snackbar(it, shape = RoundedCornerShape(6.dp), containerColor = L.accent, contentColor = L.onAccent, actionContentColor = L.onAccent)
            }
        },
        topBar = {
            Column(Modifier.background(L.bg)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("₹", color = L.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(10.dp))
                        Cap(if (showSettings) "Settings" else TABS[tab], color = L.text)
                    }
                    Cap(if (showSettings) "Close" else "Settings", Modifier.clickable { showSettings = !showSettings }.padding(8.dp))
                }
                Hairline()
            }
        },
        bottomBar = {
            Column(Modifier.background(L.bg)) {
                Hairline()
                Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    TABS.forEachIndexed { i, t ->
                        val sel = !showSettings && tab == i
                        Column(
                            Modifier.weight(1f).clickable { tab = i; showSettings = false }.padding(bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.width(24.dp).height(2.dp).background(if (sel) L.text else L.bg))
                            Spacer(Modifier.height(14.dp))
                            Cap(t, color = if (sel) L.text else L.muted)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!showSettings) FloatingActionButton(
                onClick = { vm.newDraft() },
                containerColor = L.accent, contentColor = L.onAccent, shape = RoundedCornerShape(6.dp),
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
            ) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.Light) }
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            Crossfade(if (showSettings) 99 else tab, label = "screen") { t ->
                when (t) {
                    99 -> SettingsScreen(vm, { m -> scope.launch { snackbar.showSnackbar(m) } })
                    0 -> LedgerScreen(
                        vm, status,
                        onStatus = {
                            if (!status.ok) ctx.startActivity(
                                Intent(if (mode == "A") Settings.ACTION_USAGE_ACCESS_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            ) else showSettings = true
                        },
                        onDelete = { tx ->
                            vm.delete(tx)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val r = snackbar.showSnackbar("Transaction deleted", "Undo", duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed) vm.restore(tx)
                            }
                        },
                        onPending = { p, a -> if (a == "DISMISS") vm.dismissPending(p) else vm.fromPending(p, a) }
                    )
                    1 -> AccountsScreen(vm, onTransfer = { vm.newDraft("TRANSFER") })
                    2 -> PlanScreen(vm)
                    else -> InsightsScreen(vm)
                }
            }
        }
    }

    draft?.let { d ->
        AddEditTransactionSheet(
            d, accs, expenseCats, incomeCats, recents, merchants,
            lookup = { vm.lookup(it) },
            onAddCategory = { n, k -> vm.addCategory(n, k) },
            onDismiss = { vm.draft.value = null },
            onSave = { t, r -> vm.save(t, r, d.pendingId) }
        )
    }
}
