package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

data class DetectStatus(val label: String, val ok: Boolean)

fun dayLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val t = LocalDate.now()
    return when {
        d == t -> "Today"
        d == t.minusDays(1) -> "Yesterday"
        d.year == t.year -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }
}

@Composable
fun LedgerScreen(
    vm: MainViewModel, status: DetectStatus, onStatus: () -> Unit,
    onDelete: (Txn) -> Unit, onPending: (Pending, String) -> Unit, modifier: Modifier = Modifier
) {
    val txns by vm.txns.collectAsState()
    val accs by vm.accounts.collectAsState()
    val pending by vm.pending.collectAsState()
    val streak by vm.streak.collectAsState()
    val budgets by vm.budgets.collectAsState()
    val people by vm.peopleNames.collectAsState()
    val trips by vm.trips.collectAsState()
    val showDaily by vm.prefs.showDailyCard.collectAsState()
    val paren by vm.prefs.parenNegatives.collectAsState()
    val names = remember(accs) { accs.associate { it.id to it.name } }
    val tripNames = remember(trips) { trips.associate { it.id to it.name } }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf("TODAY") }
    var selectedDay by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(period, offset) { selectedDay = 0L }

    val sel = LocalDate.now().withDayOfMonth(1).plusMonths(offset.toLong())
    val lo = dayMillis(sel)
    val hi = dayMillis(sel.plusMonths(1))
    val inMonth = txns.filter { it.date in lo until hi }
    val out = inMonth.filter { it.type == "EXPENSE" }.sumOf { it.myAmount }
    val inc = inMonth.filter { it.type == "INCOME" }.sumOf { it.amount }
    val todayStart = today()
    val dayTxns = txns.filter { it.date == todayStart }
    val dayOut = dayTxns.filter { it.type == "EXPENSE" }.sumOf { it.myAmount }
    val dayInc = dayTxns.filter { it.type == "INCOME" }.sumOf { it.amount }
    val q = query.trim()
    val isSearch = searching && q.isNotEmpty()
    fun netOf(list: List<Txn>) =
        list.filter { it.type == "INCOME" }.sumOf { it.amount } - list.filter { it.type == "EXPENSE" }.sumOf { it.myAmount }
    val dayGroups = remember(inMonth) { inMonth.groupBy { it.date }.entries.toList().sortedByDescending { it.key } }
    val shown = when {
        isSearch -> txns.filter { t ->
            t.merchant.contains(q, true) || t.note.contains(q, true) || t.category.contains(q, true) ||
                t.tag.contains(q, true) || (names[t.accountId] ?: "").contains(q, true) ||
                (tripNames[t.tripId] ?: "").contains(q, true) || fmtAmt(t.amount).contains(q)
        }
        period == "TODAY" -> dayTxns
        selectedDay != 0L -> inMonth.filter { it.date == selectedDay }
        else -> emptyList()
    }
    val groups = remember(shown) { shown.groupBy { it.date }.entries.toList().sortedByDescending { it.key } }
    val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(sel)

    // Safe-to-spend: what's left of the overall monthly cap, spread over the days that remain.
    val overall = budgets.firstOrNull { it.name == OVERALL }?.amount
    val now = LocalDate.now()
    val daysLeft = now.lengthOfMonth() - now.dayOfMonth + 1
    val counted = txns.filter { it.type == "EXPENSE" && !it.excludeFromBudget && it.date >= monthStart() }
    val spentToday = counted.filter { it.date == today() }.sumOf { it.myAmount }
    val spentBefore = counted.sumOf { it.myAmount } - spentToday
    val dailyCap = budgets.firstOrNull { it.name == DAY_OVERALL }?.amount
    val safeToday = dailyCap ?: overall?.let { maxOf(0.0, it - spentBefore) / daysLeft }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 12.dp, 24.dp, 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (period == "MONTH") Row(verticalAlignment = Alignment.CenterVertically) {
                        Cap("‹", Modifier.clickable { offset-- }.padding(end = 10.dp, top = 8.dp, bottom = 8.dp), color = L.text)
                        Cap(monthName, color = L.muted)
                        if (offset < 0) Cap("›", Modifier.clickable { offset++ }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), color = L.text)
                    } else Cap("Today", color = L.text)
                    Spacer(Modifier.weight(1f))
                    val seg = RoundedCornerShape(11.dp)
                    Row(Modifier.clip(seg).background(L.bg).border(1.dp, L.border, seg)) {
                        listOf("TODAY" to "Today", "MONTH" to "Month").forEach { (k, label) ->
                            val on = period == k
                            Box(Modifier.clip(seg).background(if (on) L.accent else Color.Transparent)
                                .clickable { period = k }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text(label, color = if (on) L.onAccent else L.muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Cap(if (searching) "Close" else "Search",
                        Modifier.clickable { searching = !searching; if (!searching) query = "" }.padding(vertical = 8.dp), color = L.text)
                }
                Spacer(Modifier.height(10.dp))
                val net = if (period == "MONTH") inc - out else dayInc - dayOut
                NetHero(if (period == "MONTH") "Month balance" else "Today’s balance", net,
                    if (period == "MONTH") out else dayOut, if (period == "MONTH") inc else dayInc, paren)
                if (period == "TODAY" && showDaily && safeToday != null && !searching) {
                    Spacer(Modifier.height(10.dp))
                    SafeStrip(safeToday - spentToday, safeToday, spentToday, daysLeft)
                }
                if (period == "TODAY" && offset == 0 && !searching) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill(status, onStatus)
                        Cap("Streak · ${streak.first}d", color = L.muted)
                        if (!streak.second) Cap("No spend today ✓", Modifier.clickable { vm.markNoSpend() }, color = L.green)
                    }
                }
                if (searching) {
                    Spacer(Modifier.height(12.dp))
                    Field("Search", query, { query = it })
                }
            }
            Hairline()
        }
        if (period == "TODAY" && offset == 0 && !isSearch && pending.isNotEmpty()) {
            item(key = "ph") { Cap("Unlogged payments · ${pending.size}", Modifier.padding(24.dp, 16.dp, 24.dp, 4.dp), color = L.red) }
            items(pending, key = { "p${it.id}" }) { p -> PendingRow(p, onPending) }
        }
        if (period == "MONTH" && !isSearch) {
            item(key = "dh") { Cap("Days", Modifier.padding(24.dp, 16.dp, 24.dp, 4.dp)) }
            items(dayGroups, key = { "d${it.key}" }) { (date, list) ->
                DaySummaryRow(dayLabel(date), netOf(list), list.size, selectedDay == date, paren) {
                    selectedDay = if (selectedDay == date) 0L else date
                }
            }
            if (selectedDay == 0L) item(key = "dtip") {
                Text("Tap a day for its entries.", color = L.muted, fontSize = 12.sp, modifier = Modifier.padding(24.dp, 8.dp))
            }
        }
        if (shown.isEmpty() && !(period == "MONTH" && !isSearch && selectedDay == 0L)) item {
            Text(
                if (isSearch) "No matches for “$q”." else if (period == "TODAY") "Nothing logged today." else "Nothing logged that day.",
                color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 8.dp)
            )
        }
        groups.forEach { (date, list) ->
            item(key = "h$date") {
                Row(Modifier.fillMaxWidth().padding(24.dp, 16.dp, 24.dp, 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Cap(dayLabel(date), color = L.muted)
                    Num(balanceText(netOf(list), paren), size = 12.sp, color = balanceColor(netOf(list)))
                }
            }
            item(key = "g$date") {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    list.forEachIndexed { i, t ->
                        TxnRow(t, names, people, tripNames, onClick = { if (!t.type.startsWith("PERSON")) vm.edit(t) },
                            onDelete = { onDelete(t) }, canDelete = !t.type.startsWith("PERSON"), divider = i < list.lastIndex)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(110.dp)) }
    }
}

@Composable
fun PendingRow(p: Pending, onAction: (Pending, String) -> Unit) {
    val whenText = Instant.ofEpochMilli(p.time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH))
    Column {
        Row(Modifier.padding(24.dp, 12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(L.red.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = L.red, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Payment via ${p.label}", color = L.text, fontSize = 15.sp, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(whenText, color = L.muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Expense", true) { onAction(p, "EXPENSE") }
                    Chip("Income", false) { onAction(p, "INCOME") }
                    Chip("Wrong call", false) { onAction(p, "DISMISS") }
                }
            }
        }
        Hairline()
    }
}

@Composable
fun StatusPill(s: DetectStatus, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).border(1.dp, L.border, shape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(if (s.ok) L.green else L.red, CircleShape))
        Spacer(Modifier.width(8.dp))
        Cap(s.label + " · " + if (s.ok) "On" else "Enable access", color = L.text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnRow(
    t: Txn, names: Map<Long, String>, people: Map<Long, String>, tripNames: Map<Long, String>, onClick: () -> Unit, onDelete: () -> Unit,
    modifier: Modifier = Modifier, divider: Boolean = true, canDelete: Boolean = true
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (canDelete && it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state = state, modifier = modifier, enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canDelete,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(L.red.copy(alpha = 0.15f)).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Cap("Delete", color = L.red)
            }
        }
    ) {
        Column(Modifier.fillMaxWidth().background(L.surface).clickable(onClick = onClick)) {
            val who = people[t.personId]
            val isPerson = t.type.startsWith("PERSON")
            val title = when (t.type) {
                "TRANSFER" -> "Transfer"
                "PERSON_OUT" -> "Lent to ${who ?: "someone"}"
                "PERSON_IN" -> "Received from ${who ?: "someone"}"
                "PERSON_ADJ" -> "${who ?: "Someone"} paid for you"
                else -> t.note.ifBlank { t.merchant }.ifBlank { t.category }
            }
            val n = itemCount(t.items)
            val sub = when {
                t.type == "TRANSFER" -> "${names[t.accountId] ?: "?"} → ${t.toAccountId?.let { names[it] } ?: "?"}"
                isPerson -> listOfNotNull(names[t.accountId], t.note.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "People" }
                else -> listOfNotNull(
                    t.merchant.takeIf { it.isNotBlank() && it != title }, t.category, names[t.accountId],
                    if (t.routeFrom.isNotBlank()) "${t.routeFrom} → ${t.routeTo}" else null,
                    tripNames[t.tripId]?.let { "trip: $it" },
                    if (n > 0) "$n items" else null,
                    who?.let { w -> if (t.owed >= t.amount - 0.005 && t.owed > 0) "$w owes all" else if (t.owed > 0) "split with $w" else "with $w" },
                    t.tag.takeIf { it.isNotBlank() }?.let { "#$it" },
                    if (t.excludeFromBudget) "excluded" else null
                ).joinToString(" · ")
            }
            Row(Modifier.fillMaxWidth().padding(18.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    t.type == "TRANSFER" -> HeadTile("Transfer", 42.dp, stored = "swap")
                    isPerson -> HeadTile(who ?: "People", 42.dp, stored = "person")
                    else -> HeadTile(t.category, 42.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = L.text, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(sub, color = L.muted, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                when (t.type) {
                    "INCOME", "PERSON_IN" -> Num("+" + inr(t.amount), color = L.green)
                    "EXPENSE", "PERSON_OUT" -> Num("−" + inr(t.amount))
                    else -> Num(inr(t.amount), color = L.muted)
                }
            }
            if (divider) Hairline(Modifier.padding(start = 74.dp))
        }
    }
}

/** Positive balances have no sign; negatives are red, with a sign or brackets by preference. */
fun balanceText(v: Double, parens: Boolean): String = when {
    v < -0.004 -> if (parens) "(${inr(v)})" else inrS(v)
    else -> inr(v)
}

@Composable
fun balanceColor(v: Double) = when {
    v < -0.004 -> L.red
    v > 0.004 -> L.green
    else -> L.text
}

@Composable
fun NetHero(label: String, net: Double, out: Double, inc: Double, parens: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(L.surface).border(1.dp, L.border, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap(label, color = L.text)
            InfoTip("Income minus expenses in this period. Transfers and people-only settlements don’t count.")
        }
        Spacer(Modifier.height(4.dp))
        Num(balanceText(net, parens), size = 38.sp, weight = FontWeight.Light, color = balanceColor(net))
        Spacer(Modifier.height(2.dp))
        Num("In ${inr(inc)}  ·  Out ${inr(out)}", size = 12.sp, color = L.muted)
    }
}

@Composable
fun SafeStrip(remaining: Double, safe: Double, spentToday: Double, daysLeft: Int) {
    val over = remaining < 0
    val frac = if (safe > 0) min(1.0, spentToday / safe).toFloat() else if (spentToday > 0) 1f else 0f
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Cap("Safe today")
            Num(inr(remaining), size = 14.sp, color = if (over) L.red else L.text)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(L.border)) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (over) L.red else L.green))
        }
        Spacer(Modifier.height(3.dp))
        Num("${inr(spentToday)} of ${inr(safe)} · $daysLeft days left", size = 11.sp, color = L.muted)
    }
}

@Composable
fun DaySummaryRow(label: String, net: Double, count: Int, expanded: Boolean, parens: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .background(if (expanded) L.surface else Color.Transparent).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Cap(label, color = if (expanded) L.text else L.muted)
            Num("$count entries", size = 11.sp, color = L.muted)
        }
        Num(balanceText(net, parens), size = 14.sp, color = balanceColor(net))
        Spacer(Modifier.width(8.dp))
        Cap(if (expanded) "⌃" else "⌄", color = L.muted)
    }
}
