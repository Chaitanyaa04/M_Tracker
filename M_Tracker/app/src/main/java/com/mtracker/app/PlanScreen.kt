package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.min

@Composable
fun MonthPlan(vm: MainViewModel, modifier: Modifier = Modifier) {
    val txns by vm.txns.collectAsState()
    val budgets by vm.budgets.collectAsState()
    val heads by vm.categories.collectAsState()
    val usage by vm.usage.collectAsState()
    var edit by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val start = monthStart()
    val counted = txns.filter { it.type == "EXPENSE" && !it.excludeFromBudget && it.date >= start }
    val byCat = counted.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.myAmount } }
    val caps = budgets.filter { !it.name.startsWith(DAY_PREFIX) }.associate { it.name to it.amount }
    val total = counted.sumOf { it.myAmount }
    val now = LocalDate.now()
    val pace = now.dayOfMonth.toDouble() / now.lengthOfMonth()
    val rows = (byCat.keys + caps.keys.filter { it != OVERALL }).distinct()
        .sortedWith(compareByDescending<String> { caps.containsKey(it) }.thenByDescending { byCat[it] ?: 0.0 })

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 12.dp, 24.dp, 16.dp)) {
                Cap("Counted spend this month")
                Spacer(Modifier.height(6.dp))
                Num(inr(total), size = 46.sp, weight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Text("The small tick on each bar is where you’d be if you spent evenly through the month. Caps reset on the 1st.", color = L.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Hairline()
        }
        item { BudgetRow("Overall", total, caps[OVERALL], pace, overall = true) { edit = OVERALL } }
        rows.forEach { c -> item(key = c) { BudgetRow(c, byCat[c] ?: 0.0, caps[c], pace) { edit = c } } }
        item { Box(Modifier.padding(24.dp)) { MonoButton("+ Cap for another head", { picking = true }, filled = false) } }
        item { Spacer(Modifier.height(110.dp)) }
    }

    if (picking) HeadsSheet(
        heads = heads, initialKind = "EXPENSE", selected = null, usage = usage, likely = { vm.likely(it) }, showKindSwitch = false,
        onPick = { n, _ -> picking = false; edit = n },
        onCreate = { n, k, ic -> vm.addHead(n, k, ic); picking = false; edit = n },
        onDismiss = { picking = false }
    )

    edit?.let { key ->
        var v by remember(key) { mutableStateOf(caps[key]?.let { fmtAmt(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { edit = null }, containerColor = L.surface, shape = RoundedCornerShape(10.dp),
            title = { Cap("Monthly cap · " + if (key == OVERALL) "Overall" else key, color = L.text) },
            text = { Field("Amount (INR)", v, { v = it }, kb = KeyboardType.Decimal) },
            confirmButton = { TextButton({ vm.setBudget(key, v.toDoubleOrNull() ?: 0.0); edit = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton({ vm.setBudget(key, 0.0); edit = null }) { Text("Clear") }
                    TextButton({ edit = null }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
fun BudgetRow(title: String, spent: Double, cap: Double?, pace: Double, overall: Boolean = false, onClick: () -> Unit) {
    val over = cap != null && spent > cap
    val ahead = cap != null && !over && cap > 0 && spent / cap > pace + 0.1
    Column {
        Column(Modifier.clickable(onClick = onClick).padding(24.dp, 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (overall) Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(L.text.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Flag, null, tint = L.text, modifier = Modifier.size(19.dp)) }
                else HeadTile(title, 34.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, color = L.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Num(inr(spent) + (if (cap != null) " / " + inr(cap) else ""), size = 13.sp, color = if (over) L.red else L.text)
            }
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp)) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(3.dp).background(L.border)) {
                    if (cap != null && cap > 0) Box(
                        Modifier.fillMaxWidth(min(1.0, spent / cap).toFloat()).fillMaxHeight()
                            .background(if (over) L.red else L.text)
                    )
                }
                if (cap != null) Box(
                    Modifier.align(Alignment.CenterStart).offset(x = maxWidth * pace.toFloat()).width(2.dp).height(8.dp).background(L.muted)
                )
            }
            Spacer(Modifier.height(8.dp))
            val note = when {
                cap == null -> "No cap · tap to set"
                over -> "Exceeded by " + inr(spent - cap)
                ahead -> inr(cap - spent) + " left · ahead of pace"
                else -> inr(cap - spent) + " left · on pace"
            }
            Num(note, size = 12.sp, color = if (over || ahead) L.red else L.muted)
        }
        Hairline()
    }
}

@Composable
fun PlanScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(24.dp, 4.dp, 24.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Today", view == 0) { view = 0 }
            Chip("This month", view == 1) { view = 1 }
        }
        if (view == 0) DayPlan(vm, Modifier.weight(1f)) else MonthPlan(vm, Modifier.weight(1f))
    }
}

/** The same idea as the monthly plan, for a single day. */
@Composable
fun DayPlan(vm: MainViewModel, modifier: Modifier = Modifier) {
    val txns by vm.txns.collectAsState()
    val budgets by vm.budgets.collectAsState()
    val heads by vm.categories.collectAsState()
    val usage by vm.usage.collectAsState()
    var edit by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val t0 = today()
    val counted = txns.filter { it.type == "EXPENSE" && !it.excludeFromBudget }
    val todays = counted.filter { it.date == t0 }
    val byHead = todays.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.myAmount } }
    val total = todays.sumOf { it.myAmount }
    val caps = budgets.filter { it.name.startsWith(DAY_PREFIX) }.associate { it.name.removePrefix(DAY_PREFIX) to it.amount }
    val monthly = budgets.firstOrNull { it.name == OVERALL }?.amount
    val now = LocalDate.now()
    val daysLeft = now.lengthOfMonth() - now.dayOfMonth + 1
    val spentBefore = counted.filter { it.date >= monthStart() && it.date < t0 }.sumOf { it.myAmount }
    val auto = monthly?.let { maxOf(0.0, it - spentBefore) / daysLeft }
    val limit = caps[OVERALL] ?: auto
    val pace = java.time.LocalTime.now().toSecondOfDay() / 86400.0
    val rows = (byHead.keys + caps.keys.filter { it != OVERALL }).distinct()
        .sortedWith(compareByDescending<String> { caps.containsKey(it) }.thenByDescending { byHead[it] ?: 0.0 })

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 12.dp, 24.dp, 16.dp)) {
                Cap("Spent today")
                Spacer(Modifier.height(6.dp))
                Num(inr(total), size = 46.sp, weight = FontWeight.Light)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        limit == null -> "Set a daily limit below, or a monthly plan to get one automatically."
                        caps[OVERALL] == null -> "Limit ${inr(limit)}, worked out from your monthly plan."
                        else -> "Your daily limit is ${inr(limit)}."
                    }, color = L.muted, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
            Hairline()
        }
        item { BudgetRow(if (caps[OVERALL] == null && auto != null) "Daily limit (auto)" else "Daily limit", total, limit, pace, overall = true) { edit = OVERALL } }
        rows.forEach { h -> item(key = "d$h") { BudgetRow(h, byHead[h] ?: 0.0, caps[h], pace) { edit = h } } }
        item { Box(Modifier.padding(24.dp)) { MonoButton("+ Daily cap for a head", { picking = true }, filled = false) } }
        item { Spacer(Modifier.height(110.dp)) }
    }

    if (picking) HeadsSheet(
        heads = heads, initialKind = "EXPENSE", selected = null, usage = usage, likely = { vm.likely(it) }, showKindSwitch = false,
        onPick = { n, _ -> picking = false; edit = n },
        onCreate = { n, k, ic -> vm.addHead(n, k, ic); picking = false; edit = n },
        onDismiss = { picking = false }
    )
    edit?.let { key ->
        var v by remember(key) { mutableStateOf(caps[key]?.let { fmtAmt(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { edit = null }, containerColor = L.surface, shape = RoundedCornerShape(10.dp),
            title = { Cap("Daily cap · " + if (key == OVERALL) "Overall" else key, color = L.text) },
            text = { Field("Amount per day (INR)", v, { v = it }, kb = KeyboardType.Decimal) },
            confirmButton = { TextButton({ vm.setBudget(DAY_PREFIX + key, v.toDoubleOrNull() ?: 0.0); edit = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton({ vm.setBudget(DAY_PREFIX + key, 0.0); edit = null }) { Text("Clear") }
                    TextButton({ edit = null }) { Text("Cancel") }
                }
            }
        )
    }
}
