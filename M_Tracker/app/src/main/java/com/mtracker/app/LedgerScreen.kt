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
    val names = remember(accs) { accs.associate { it.id to it.name } }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val sel = LocalDate.now().withDayOfMonth(1).plusMonths(offset.toLong())
    val lo = dayMillis(sel)
    val hi = dayMillis(sel.plusMonths(1))
    val inMonth = txns.filter { it.date in lo until hi }
    val out = inMonth.filter { it.type == "EXPENSE" }.sumOf { it.myAmount }
    val inc = inMonth.filter { it.type == "INCOME" }.sumOf { it.amount }
    val q = query.trim()
    val isSearch = searching && q.isNotEmpty()
    val shown = if (isSearch) txns.filter { t ->
        t.merchant.contains(q, true) || t.note.contains(q, true) || t.category.contains(q, true) ||
            t.tag.contains(q, true) || (names[t.accountId] ?: "").contains(q, true) || fmtAmt(t.amount).contains(q)
    } else inMonth
    val groups = remember(shown) { shown.groupBy { it.date }.entries.toList() }
    val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(sel)

    // Safe-to-spend: what's left of the overall monthly cap, spread over the days that remain.
    val overall = budgets.firstOrNull { it.name == OVERALL }?.amount
    val now = LocalDate.now()
    val daysLeft = now.lengthOfMonth() - now.dayOfMonth + 1
    val counted = txns.filter { it.type == "EXPENSE" && !it.excludeFromBudget && it.date >= monthStart() }
    val spentToday = counted.filter { it.date == today() }.sumOf { it.myAmount }
    val spentBefore = counted.sumOf { it.myAmount } - spentToday
    val safeToday = overall?.let { maxOf(0.0, it - spentBefore) / daysLeft }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 12.dp, 24.dp, 22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cap("‹", Modifier.clickable { offset-- }.padding(end = 12.dp, top = 8.dp, bottom = 8.dp), color = L.text)
                        Cap(monthName, color = L.muted)
                        if (offset < 0) Cap("›", Modifier.clickable { offset++ }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), color = L.text)
                    }
                    Cap(if (searching) "Close" else "Search", Modifier.clickable { searching = !searching; if (!searching) query = "" }.padding(8.dp), color = L.text)
                }
                Spacer(Modifier.height(10.dp))
                if (offset == 0 && safeToday != null && !searching) {
                    val remaining = safeToday - spentToday
                    HeroCard(remaining, safeToday, spentToday, daysLeft, maxOf(0.0, (overall ?: 0.0) - spentBefore - spentToday))
                    Spacer(Modifier.height(12.dp))
                    Num("Net spend ${inrS(out - inc)}  ·  Out ${inr(out)}  ·  In ${inr(inc)}", size = 12.sp, color = L.muted)
                } else {
                    Cap("Net spend")
                    Spacer(Modifier.height(6.dp))
                    Num(inrS(out - inc), size = 52.sp, weight = FontWeight.Light)
                    Spacer(Modifier.height(4.dp))
                    Num("Out ${inr(out)}  ·  In ${inr(inc)}", size = 13.sp, color = L.muted)
                    if (offset == 0 && overall == null && !searching)
                        Text("Set an overall monthly budget in Plan to see your daily safe-to-spend.", color = L.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (offset == 0) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill(status, onStatus)
                        Cap("Streak · ${streak.first} day" + if (streak.first == 1) "" else "s", color = L.muted)
                    }
                    if (!streak.second) {
                        Spacer(Modifier.height(12.dp))
                        MonoButton("No spending today", { vm.markNoSpend() }, filled = false)
                    }
                }
                if (searching) {
                    Spacer(Modifier.height(16.dp))
                    Field("Search merchant, note, tag, amount", query, { query = it })
                }
            }
            Hairline()
        }
        if (offset == 0 && !isSearch && pending.isNotEmpty()) {
            item(key = "ph") { Cap("Unlogged payments · ${pending.size}", Modifier.padding(24.dp, 20.dp, 24.dp, 4.dp), color = L.red) }
            items(pending, key = { "p${it.id}" }) { p -> PendingRow(p, onPending) }
        }
        if (shown.isEmpty()) item {
            Text(
                if (isSearch) "No matches for “$q”." else if (offset == 0 && txns.isEmpty()) "No transactions yet. Tap the + button to add your first one." else "Nothing logged in $monthName.",
                color = L.muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)
            )
        }
        groups.forEach { (date, list) ->
            item(key = "h$date") {
                val dayNet = list.sumOf { if (it.type == "EXPENSE") -it.myAmount else if (it.type == "INCOME") it.amount else 0.0 }
                Row(Modifier.fillMaxWidth().padding(24.dp, 20.dp, 24.dp, 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Cap(dayLabel(date), color = L.muted)
                    Num(inrS(dayNet), size = 12.sp, color = L.muted)
                }
            }
            item(key = "g$date") {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    list.forEachIndexed { i, t ->
                        TxnRow(t, names, people, onClick = { if (!t.type.startsWith("PERSON")) vm.edit(t) }, onDelete = { onDelete(t) }, divider = i < list.lastIndex)
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
                val title = (p.amount?.let { inr(it) } ?: "Payment") + (p.merchant?.let { " at $it" } ?: "") + " via ${p.label}"
                Text(title, color = L.text, fontSize = 15.sp, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(whenText, color = L.muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Expense", true) { onAction(p, "EXPENSE") }
                    Chip("Income", false) { onAction(p, "INCOME") }
                    Chip("Dismiss", false) { onAction(p, "DISMISS") }
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
    t: Txn, names: Map<Long, String>, people: Map<Long, String>, onClick: () -> Unit, onDelete: () -> Unit,
    modifier: Modifier = Modifier, divider: Boolean = true
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state, modifier, enableDismissFromStartToEnd = false,
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
                "PERSON_OUT" -> "Gave ${who ?: "someone"}"
                "PERSON_IN" -> "Got from ${who ?: "someone"}"
                "PERSON_ADJ" -> "${who ?: "Someone"} paid for you"
                else -> t.merchant.ifBlank { t.note.ifBlank { t.category } }
            }
            val n = itemCount(t.items)
            val sub = when {
                t.type == "TRANSFER" -> "${names[t.accountId] ?: "?"} → ${t.toAccountId?.let { names[it] } ?: "?"}"
                isPerson -> listOfNotNull(names[t.accountId], t.note.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "People" }
                else -> listOfNotNull(
                    t.category, names[t.accountId],
                    if (t.routeFrom.isNotBlank()) "${t.routeFrom} → ${t.routeTo}" else null,
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

/** Today's safe-to-spend as a calm ring. */
@Composable
fun HeroCard(remaining: Double, safe: Double, spentToday: Double, daysLeft: Int, monthLeft: Double) {
    val over = remaining < 0
    val frac = if (safe > 0) min(1.0, spentToday / safe).toFloat() else if (spentToday > 0) 1f else 0f
    val ring by androidx.compose.animation.core.animateFloatAsState(frac, androidx.compose.animation.core.tween(900), label = "ring")
    val shown by androidx.compose.animation.core.animateFloatAsState(remaining.toFloat(), androidx.compose.animation.core.tween(700), label = "count")
    val track = L.border; val ink = if (over) L.red else L.text
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(L.surface, androidx.compose.ui.graphics.lerp(L.surface, L.bg, 0.6f))))
            .border(1.dp, L.border, shape).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Cap("Safe to spend today")
            Spacer(Modifier.height(8.dp))
            Num((if (shown < 0) "−" else "") + inr(shown.toDouble()), size = 36.sp, weight = FontWeight.Light, color = if (over) L.red else L.text)
            Spacer(Modifier.height(8.dp))
            Text("${inr(spentToday)} spent of ${inr(safe)}", color = L.muted, fontSize = 12.sp)
            Text("${inr(monthLeft)} left this month · $daysLeft days", color = L.muted, fontSize = 12.sp)
        }
        androidx.compose.foundation.Canvas(Modifier.size(84.dp)) {
            val w = 9.dp.toPx()
            val inset = w / 2
            val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(ink, -90f, 360f * ring, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
