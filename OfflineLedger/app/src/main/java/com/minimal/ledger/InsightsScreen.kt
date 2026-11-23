package com.minimal.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun InsightsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val txns by vm.txns.collectAsState()
    var range by rememberSaveable { mutableIntStateOf(0) }
    val ranges = listOf("1M" to 1, "3M" to 3, "6M" to 6, "1Y" to 12, "All" to 0)
    val months = ranges[range].second
    val from = if (months == 0) 0L
    else LocalDate.now().minusMonths(months.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val ex = remember(txns, from) { txns.filter { it.type == "EXPENSE" && it.date >= from } }
    val total = ex.sumOf { it.amount }
    val cats = remember(ex) { ex.groupBy { it.category }.map { it.key to it.value.sumOf { t -> t.amount } }.sortedByDescending { it.second } }
    val merchants = remember(ex) {
        ex.filter { it.merchant.isNotBlank() }.groupBy { it.merchant.trim() }
            .map { it.key to it.value.sumOf { t -> t.amount } }.sortedByDescending { it.second }
    }
    val tags = remember(ex) {
        ex.filter { it.tag.isNotBlank() }.groupBy { "#" + it.tag.trim() }
            .map { it.key to it.value.sumOf { t -> t.amount } }.sortedByDescending { it.second }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 16.dp, 24.dp, 16.dp)) {
                Cap("Total spend")
                Spacer(Modifier.height(8.dp))
                Num(inr(total), size = 44.sp, weight = FontWeight.Light)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ranges.forEachIndexed { i, r -> Chip(r.first, range == i) { range = i } }
                }
            }
            Hairline()
        }
        item { Ranked("Spend by category", cats) }
        item { Ranked("Top merchants", merchants) }
        item { Ranked("Spend by tag", tags) }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
fun Ranked(title: String, rows: List<Pair<String, Double>>, limit: Int = 8) {
    Column {
        Cap(title, Modifier.padding(24.dp, 28.dp, 24.dp, 8.dp))
        Hairline()
        if (rows.isEmpty()) Text("No data in this period", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 14.dp))
        val max = rows.maxOfOrNull { it.second } ?: 1.0
        rows.take(limit).forEach { (n, v) ->
            Column(Modifier.padding(24.dp, 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(n, color = L.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Num(inr(v), size = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(L.border)) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(L.text))
                }
            }
            Hairline()
        }
    }
}
