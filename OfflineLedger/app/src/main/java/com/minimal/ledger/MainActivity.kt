package com.minimal.ledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Reminder notification actions (Expense / Income) arrive here as extras. */
    private fun handle(i: Intent?) {
        val type = i?.getStringExtra(Extras.TYPE) ?: return
        NotificationManagerCompat.from(this).cancel(i.getIntExtra(Extras.NOTIF_ID, -1))
        val amount = if (i.hasExtra(Extras.AMOUNT)) i.getDoubleExtra(Extras.AMOUNT, 0.0) else null
        vm.fromReminder(type, i.getLongExtra(Extras.ACCOUNT, 0L), amount, i.getStringExtra(Extras.MERCHANT))
        i.removeExtra(Extras.TYPE)
    }
}

private val TABS = listOf("Ledger", "Accounts", "Plan", "Insights")

@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val theme by vm.prefs.theme.collectAsState()
    val mode by vm.prefs.mode.collectAsState()
    val apps by vm.prefs.apps.collectAsState()
    var tick by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; MonitorController.sync(ctx) }
    LaunchedEffect(mode, apps) { MonitorController.sync(ctx) }

    LedgerTheme(theme) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val draft by vm.draft.collectAsState()
        val accs by vm.accounts.collectAsState()

        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        BackHandler(showSettings) { showSettings = false }

        val status = remember(tick, mode) {
            if (mode == "A") DetectStatus("App-Launch Guard", Perms.usage(ctx))
            else DetectStatus("Notification Parser", Perms.listener(ctx))
        }

        Scaffold(
            containerColor = L.bg,
            snackbarHost = {
                SnackbarHost(snackbar) {
                    Snackbar(it, shape = RoundedCornerShape(6.dp), containerColor = L.accent, contentColor = L.onAccent,
                        actionContentColor = L.onAccent)
                }
            },
            topBar = {
                Column(Modifier.background(L.bg)) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cap(if (showSettings) "Settings" else TABS[tab], color = L.text)
                        Cap(if (showSettings) "Close" else "Settings", Modifier.clickable { showSettings = !showSettings })
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
                if (showSettings) {
                    SettingsScreen(vm, { m -> scope.launch { snackbar.showSnackbar(m) } })
                } else when (tab) {
                    0 -> LedgerScreen(
                        vm, status,
                        onStatus = {
                            if (!status.ok) ctx.startActivity(
                                Intent(if (mode == "A") Settings.ACTION_USAGE_ACCESS_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            ) else showSettings = true
                        },
                        onDelete = { t ->
                            vm.delete(t)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val r = snackbar.showSnackbar("Transaction deleted", "Undo", duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed) vm.restore(t)
                            }
                        }
                    )
                    1 -> AccountsScreen(vm, onTransfer = { vm.newDraft("TRANSFER") })
                    2 -> PlanScreen(vm)
                    else -> InsightsScreen(vm)
                }
            }
        }

        draft?.let { d ->
            AddEditTransactionSheet(d, accs, onDismiss = { vm.draft.value = null }, onSave = { vm.save(it) })
        }
    }
}
