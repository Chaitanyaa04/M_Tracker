package com.minimal.ledger

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
    onDelete: (Txn) -> Unit, modifier: Modifier = Modifier
) {
    val txns by vm.txns.collectAsState()
    val accs by vm.accounts.collectAsState()
    val names = remember(accs) { accs.associate { it.id to it.name } }
    val start = monthStart()
    val month = txns.filter { it.date >= start }
    val out = month.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val inc = month.filter { it.type == "INCOME" }.sumOf { it.amount }
    val groups = remember(txns) { txns.groupBy { it.date }.entries.toList() }
    val monthName = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH).format(LocalDate.now())

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 16.dp, 24.dp, 24.dp)) {
                Cap("Net spend · $monthName")
                Spacer(Modifier.height(8.dp))
                Num(inrS(out - inc), size = 44.sp, weight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Num("Out ${inr(out)}  ·  In ${inr(inc)}", size = 13.sp, color = L.muted)
                Spacer(Modifier.height(16.dp))
                StatusPill(status, onStatus)
            }
            Hairline()
        }
        if (txns.isEmpty()) item {
            Text("No transactions yet. Tap + to add one.", color = L.muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
        groups.forEach { (date, list) ->
            item(key = "h$date") { Cap(dayLabel(date), Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp)) }
            items(list, key = { it.id }) { t ->
                TxnRow(t, names, onClick = { vm.edit(t) }, onDelete = { onDelete(t) })
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
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
fun TxnRow(t: Txn, names: Map<Long, String>, onClick: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = false,
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
