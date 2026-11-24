package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val names = remember(accs) { accs.associate { it.id to it.name } }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val sel = LocalDate.now().withDayOfMonth(1).plusMonths(offset.toLong())
    val lo = dayMillis(sel)
    val hi = dayMillis(sel.plusMonths(1))
    val inMonth = txns.filter { it.date in lo until hi }
    val out = inMonth.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val inc = inMonth.filter { it.type == "INCOME" }.sumOf { it.amount }
    val q = query.trim()
    val isSearch = searching && q.isNotEmpty()
    val shown = if (isSearch) txns.filter { t ->
        t.merchant.contains(q, true) || t.note.contains(q, true) || t.category.contains(q, true) ||
            t.tag.contains(q, true) || (names[t.accountId] ?: "").contains(q, true) || fmtAmt(t.amount).contains(q)
    } else inMonth
    val groups = remember(shown) { shown.groupBy { it.date }.entries.toList() }
    val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(sel)

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 12.dp, 24.dp, 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cap("‹", Modifier.clickable { offset-- }.padding(end = 12.dp, top = 8.dp, bottom = 8.dp), color = L.text)
                        Cap("Net spend · $monthName", color = L.muted)
                        if (offset < 0) Cap("›", Modifier.clickable { offset++ }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), color = L.text)
                    }
                    Cap(if (searching) "Close" else "Search", Modifier.clickable { searching = !searching; if (!searching) query = "" }.padding(8.dp), color = L.text)
                }
                Spacer(Modifier.height(8.dp))
                Num(inrS(out - inc), size = 44.sp, weight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Num("Out ${inr(out)}  ·  In ${inr(inc)}", size = 13.sp, color = L.muted)
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
                if (isSearch) "No matches for “$q”." else if (offset == 0 && txns.isEmpty()) "No transactions yet. Tap + to add your first one." else "Nothing logged in $monthName.",
                color = L.muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)
            )
        }
        groups.forEach { (date, list) ->
            item(key = "h$date") { Cap(dayLabel(date), Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp)) }
            items(list, key = { it.id }) { t ->
                TxnRow(t, names, onClick = { vm.edit(t) }, onDelete = { onDelete(t) }, modifier = Modifier.animateItem())
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
fun PendingRow(p: Pending, onAction: (Pending, String) -> Unit) {
    val whenText = Instant.ofEpochMilli(p.time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH))
    Column {
        Column(Modifier.padding(24.dp, 12.dp)) {
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
        Hairline()
    }
}

@Composable
fun StatusPill(s: DetectStatus, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
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
fun TxnRow(t: Txn, names: Map<Long, String>, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
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
        Column(Modifier.fillMaxWidth().background(L.bg).clickable(onClick = onClick)) {
            val title = if (t.type == "TRANSFER") "Transfer" else t.merchant.ifBlank { t.note.ifBlank { t.category } }
            val sub = if (t.type == "TRANSFER")
                "${names[t.accountId] ?: "?"} → ${t.toAccountId?.let { names[it] } ?: "?"}"
            else listOfNotNull(
                t.category, names[t.accountId],
                t.tag.takeIf { it.isNotBlank() }?.let { "#$it" },
                if (t.excludeFromBudget) "excluded" else null
            ).joinToString(" · ")
            Row(Modifier.fillMaxWidth().padding(24.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = L.text, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(sub, color = L.muted, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                when (t.type) {
                    "INCOME" -> Num("+" + inr(t.amount), color = L.green)
                    "EXPENSE" -> Num("−" + inr(t.amount))
                    else -> Num(inr(t.amount), color = L.muted)
                }
            }
            Hairline()
        }
    }
}
