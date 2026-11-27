package com.mtracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.abs

private fun typeName(t: String) = when (t) {
    "CASH" -> "Cash"
    "CARD" -> "Credit card"
    "ASSET" -> "Portfolio"
    else -> "Bank"
}

private fun delta(t: Txn, id: Long): Double = when (t.type) {
    "INCOME", "PERSON_IN" -> t.amount
    "EXPENSE", "PERSON_OUT" -> -t.amount
    "PERSON_ADJ" -> 0.0
    else -> if (t.accountId == id) -t.amount else t.amount
}

/** Balance of one account over the last [days] days, for the card sparkline. */
fun accountSeries(a: Account, txns: List<Txn>, days: Int = 30): List<Double> {
    val flows = txns.filter { it.accountId == a.id || it.toAccountId == a.id }.sortedBy { it.date }
    val start = LocalDate.now().minusDays((days - 1).toLong())
    var run = a.openingBalance
    var idx = 0
    val out = ArrayList<Double>(days)
    for (i in 0 until days) {
        val dm = dayMillis(start.plusDays(i.toLong()))
        while (idx < flows.size && flows[idx].date <= dm) { run += delta(flows[idx], a.id); idx++ }
        out.add(run)
    }
    return out
}

@Composable
fun Sparkline(v: List<Double>, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        if (v.size < 2) return@Canvas
        val mn = v.min(); val mx = v.max()
        val span = if (mx - mn > 0) mx - mn else 1.0
        val pad = 3.dp.toPx()
        val path = Path()
        v.forEachIndexed { i, y ->
            val px = size.width * i / (v.size - 1)
            val py = size.height - pad - ((y - mn) / span * (size.height - 2 * pad)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun AccountCard(a: Account, balance: Double, series: List<Double>, hide: Boolean) {
    val base = cardColor(a.color)
    val white = Color.White
    Box(
        Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(base, lerp(base, Color.Black, 0.5f))))
            .padding(20.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(a.name, color = white, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                Cap(typeName(a.type), color = white.copy(alpha = 0.6f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Cap(if (a.type == "CARD" && balance < 0) "Owed" else if (a.type == "ASSET") "Value" else "Balance", color = white.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    val txt = if (hide) "••••••" else if (a.type == "CARD" && balance < 0) inr(balance) else inrS(balance)
                    Num(txt, size = 30.sp, color = white, weight = FontWeight.Light)
                }
                Sparkline(series, Modifier.width(96.dp).height(46.dp), white.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().height(184.dp).clip(shape).border(1.dp, L.border, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", color = L.text, fontSize = 36.sp, fontWeight = FontWeight.Light)
            Cap("Add account")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column { Cap(label); Spacer(Modifier.height(4.dp)); Num(value, size = 15.sp) }
}

@Composable
fun WalletView(vm: MainViewModel, onTransfer: () -> Unit, modifier: Modifier = Modifier) {
    val accs by vm.accounts.collectAsState()
    val bal by vm.balances.collectAsState()
    val txns by vm.txns.collectAsState()
    val hide by vm.prefs.hideBalances.collectAsState()
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    val pager = rememberPagerState { accs.size + 1 }
    fun m(s: String) = if (hide) "••••••" else s
    fun total(type: String) = accs.filter { it.type == type }.sumOf { bal[it.id] ?: 0.0 }
    val net = accs.sumOf { bal[it.id] ?: 0.0 }
    val cashBank = total("BANK") + total("CASH")
    val owed = -total("CARD")
    val names = remember(accs) { accs.associate { it.id to it.name } }
    val cur = accs.getOrNull(pager.currentPage)

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 12.dp, 24.dp, 18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Cap("Net worth")
                    Icon(
                        if (hide) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Toggle balances", tint = L.text,
                        modifier = Modifier.size(22.dp).clickable { vm.prefs.setHide(!hide) }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Num(m(inrS(net)), size = 46.sp, weight = FontWeight.Light, color = if (net < 0 && !hide) L.red else L.text)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Stat("Cash & bank", m(inrS(cashBank)))
                    Stat("Cards owed", m(inr(owed)))
                    Stat("Portfolio", m(inrS(total("ASSET"))))
                }
            }
        }
        item {
            HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 24.dp), pageSpacing = 12.dp) { i ->
                if (i < accs.size) {
                    val a = accs[i]
                    AccountCard(a, bal[a.id] ?: 0.0, remember(a, txns) { accountSeries(a, txns) }, hide)
                } else AddCard { adding = true }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                repeat(accs.size + 1) { i ->
                    Box(Modifier.padding(3.dp).size(if (i == pager.currentPage) 7.dp else 5.dp).background(if (i == pager.currentPage) L.text else L.border, CircleShape))
                }
            }
            Row(Modifier.padding(24.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoButton("Transfer", onTransfer)
                if (cur != null) MonoButton("Edit", { editing = cur }, filled = false)
                MonoButton("+ Account", { adding = true }, filled = false)
            }
            Hairline()
        }
        if (cur != null) {
            val mine = txns.filter { it.accountId == cur.id || it.toAccountId == cur.id }.take(15)
            item(key = "rh") { Cap("Recent · ${cur.name}", Modifier.padding(24.dp, 20.dp, 24.dp, 6.dp)) }
            if (mine.isEmpty()) item(key = "re") { Text("No activity yet.", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 8.dp)) }
            items(mine, key = { "a${cur.id}-${it.id}" }) { t ->
                val d = delta(t, cur.id)
                Column {
                    Row(Modifier.fillMaxWidth().padding(24.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.type == "TRANSFER") HeadTile("Transfer", 38.dp, stored = "swap") else HeadTile(t.category, 38.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (t.type == "TRANSFER") "Transfer" else t.note.ifBlank { t.merchant }.ifBlank { t.category }, color = L.text, fontSize = 15.sp, maxLines = 1)
                            Text(
                                dayLabel(t.date) + if (t.type == "TRANSFER") " · ${names[t.accountId] ?: "?"} → ${t.toAccountId?.let { names[it] } ?: "?"}" else " · ${t.category}",
                                color = L.muted, fontSize = 12.sp, maxLines = 1
                            )
                        }
                        Num((if (d >= 0) "+" else "−") + inr(d), size = 15.sp, color = if (d >= 0) L.green else L.text)
                    }
                    Hairline(Modifier.padding(start = 76.dp))
                }
            }
        }
        item { Spacer(Modifier.height(110.dp)) }
    }

    if (adding) AccountDialog(null, { adding = false }, { vm.saveAccount(it); adding = false }, {})
    editing?.let { a ->
        AccountDialog(a, { editing = null }, { vm.saveAccount(it); editing = null }, { vm.deleteAccount(a.id); editing = null })
    }
}

@Composable
fun AccountDialog(account: Account?, onDismiss: () -> Unit, onSave: (Account) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var type by remember { mutableStateOf(account?.type ?: "BANK") }
    var color by remember { mutableIntStateOf(account?.color ?: ((name.length + 3) % CARD_COLORS.size)) }
    var openingText by remember {
        mutableStateOf(account?.let { fmtAmt(if (it.type == "CARD") -it.openingBalance else it.openingBalance) } ?: "")
    }
    val openLabel = when (type) {
        "CARD" -> "Amount owed (INR)"
        "ASSET" -> "Current value (INR)"
        else -> "Opening balance (INR)"
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(10.dp),
        title = { Cap(if (account == null) "New account" else "Edit account", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Name", name, { name = it })
                Picker(
                    "Type", typeName(type),
                    listOf("Bank account" to "BANK", "Cash" to "CASH", "Credit card" to "CARD", "Portfolio asset" to "ASSET"),
                    { type = it }
                )
                Field(openLabel, openingText, { openingText = it }, kb = KeyboardType.Decimal)
                Cap("Card colour")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    CARD_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).background(c, CircleShape)
                                .border(if (i == color) 2.dp else 1.dp, if (i == color) L.text else L.border, CircleShape)
                                .clickable { color = i }
                        )
                    }
                }
                if (account != null) Text("Deleting an account also removes its transactions.", color = L.muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton({
                val v = openingText.toDoubleOrNull() ?: 0.0
                if (name.isNotBlank()) onSave(Account(account?.id ?: 0, name.trim(), type, if (type == "CARD") -abs(v) else v, color))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (account != null) TextButton(onDelete) { Text("Delete", color = L.red) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Wallet cards for your accounts, and a People view for who owes whom. */
@Composable
fun AccountsScreen(vm: MainViewModel, onTransfer: () -> Unit, modifier: Modifier = Modifier) {
    var view by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(24.dp, 4.dp, 24.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Wallet", view == 0) { view = 0 }
            Chip("People", view == 1) { view = 1 }
        }
        if (view == 0) WalletView(vm, onTransfer, Modifier.weight(1f)) else PeopleView(vm, Modifier.weight(1f))
    }
}
