package com.minimal.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

@Composable
fun PlanScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val txns by vm.txns.collectAsState()
    val budgets by vm.budgets.collectAsState()
    var edit by remember { mutableStateOf<String?>(null) }
    val start = monthStart()
    val counted = txns.filter { it.type == "EXPENSE" && !it.excludeFromBudget && it.date >= start }
    val byCat = counted.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
    val caps = budgets.associate { it.name to it.amount }
    val total = counted.sumOf { it.amount }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 16.dp, 24.dp, 16.dp)) {
                Cap("Counted spend this month")
                Spacer(Modifier.height(8.dp))
                Num(inr(total), size = 44.sp, weight = FontWeight.Light)
            }
            Hairline()
        }
        item { BudgetRow("Overall", total, caps[OVERALL]) { edit = OVERALL } }
        CATEGORIES.forEach { c ->
            item(key = c) { BudgetRow(c, byCat[c] ?: 0.0, caps[c]) { edit = c } }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    edit?.let { key ->
        var v by remember(key) { mutableStateOf(caps[key]?.let { fmtAmt(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { edit = null }, containerColor = L.surface, shape = RoundedCornerShape(6.dp),
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
fun BudgetRow(title: String, spent: Double, cap: Double?, onClick: () -> Unit) {
    val over = cap != null && spent > cap
    Column {
        Column(Modifier.clickable(onClick = onClick).padding(24.dp, 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = L.text, fontSize = 15.sp)
                Num(inr(spent) + (if (cap != null) " / " + inr(cap) else ""), size = 13.sp, color = if (over) L.red else L.text)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(L.border)) {
                if (cap != null && cap > 0) {
                    Box(
                        Modifier.fillMaxWidth(min(1.0, spent / cap).toFloat()).fillMaxHeight()
                            .background(if (over) L.red else L.text)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val note = when {
                cap == null -> "No cap · tap to set"
                over -> "Exceeded by " + inr(spent - cap)
                else -> inr(cap - spent) + " left"
            }
            Num(note, size = 12.sp, color = if (over) L.red else L.muted)
        }
        Hairline()
    }
}
