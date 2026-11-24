package com.mtracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

fun trendSeries(txns: List<Txn>, accs: List<Account>, lo: Long, hi: Long): List<Double> {
    val z = ZoneId.systemDefault()
    val opening = accs.sumOf { it.openingBalance }
    val flows = txns.filter { it.type != "TRANSFER" }.sortedBy { it.date }
    if (flows.isEmpty()) return emptyList()
    val d0 = Instant.ofEpochMilli(if (lo == 0L) flows.first().date else lo).atZone(z).toLocalDate()
    val d1 = Instant.ofEpochMilli(minOf(hi, today())).atZone(z).toLocalDate()
    val n = (ChronoUnit.DAYS.between(d0, d1).toInt() + 1).coerceIn(1, 4000)
    val out = ArrayList<Double>(n)
    var run = 0.0
    var idx = 0
    for (i in 0 until n) {
        val dm = dayMillis(d0.plusDays(i.toLong()))
        while (idx < flows.size && flows[idx].date <= dm) {
            val t = flows[idx++]
            run += if (t.type == "INCOME") t.amount else -t.amount
        }
        out.add(opening + run)
    }
    if (out.size <= 240) return out
    val step = out.size / 240.0
    return (0 until 240).map { out[(it * step).toInt()] } + out.last()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val txns by vm.txns.collectAsState()
    val accs by vm.accounts.collectAsState()
    var range by rememberSaveable { mutableIntStateOf(0) }
    var cFrom by rememberSaveable { mutableLongStateOf(0L) }
    var cTo by rememberSaveable { mutableLongStateOf(0L) }
    var showRange by remember { mutableStateOf(false) }
    var flowMode by rememberSaveable { mutableIntStateOf(0) }
    val ranges = listOf("1M" to 1, "3M" to 3, "6M" to 6, "1Y" to 12, "All" to 0)

    val (lo, hi) = if (range == 5 && cTo != 0L) cFrom to cTo
    else if (range == 5) 0L to Long.MAX_VALUE
    else {
        val m = ranges[range].second
        (if (m == 0) 0L else dayMillis(LocalDate.now().minusMonths(m.toLong()))) to Long.MAX_VALUE
    }
    val ex = remember(txns, lo, hi) { txns.filter { it.type == "EXPENSE" && it.date in lo..hi } }
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
    val series = remember(txns, accs, lo, hi) { trendSeries(txns, accs, lo, hi) }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 16.dp, 24.dp, 16.dp)) {
                Cap("Total spend")
                Spacer(Modifier.height(8.dp))
                Num(inr(total), size = 44.sp, weight = FontWeight.Light)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ranges.forEachIndexed { i, r -> Chip(r.first, range == i) { range = i } }
                    Chip(if (range == 5 && cTo != 0L) "Custom ✓" else "Custom", range == 5) { showRange = true }
                }
            }
            Hairline()
        }
        item {
            Column(Modifier.padding(24.dp, 20.dp)) {
                Cap("Balance trend")
                Spacer(Modifier.height(8.dp))
                if (series.size >= 2) {
                    val first = series.first(); val last = series.last(); val delta = last - first
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Num(inrS(last), size = 26.sp, weight = FontWeight.Light)
                        Num(
                            (if (delta >= 0) "▲ +" else "▼ −") + inr(delta) +
                                (if (first != 0.0) "  " + String.format(Locale.US, "%.1f", abs(delta / first) * 100) + "%" else ""),
                            size = 13.sp, color = if (delta >= 0) L.green else L.red
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TrendChart(series)
                } else Text("Log a few transactions to see your trend.", color = L.muted, fontSize = 13.sp)
            }
            Hairline()
        }
        item { CashFlow(txns, flowMode) { flowMode = it } }
        item { Ranked("Spend by category", cats) }
        item { Ranked("Top merchants", merchants) }
        item { Ranked("Spend by tag", tags) }
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (showRange) {
        val st = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRange = false },
            confirmButton = {
                TextButton({
                    val a = st.selectedStartDateMillis; val b = st.selectedEndDateMillis
                    if (a != null && b != null) { cFrom = pickerToLocal(a); cTo = pickerToLocal(b); range = 5 }
                    showRange = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showRange = false }) { Text("Cancel") } }
        ) { DateRangePicker(st, modifier = Modifier.height(500.dp)) }
    }
}

@Composable
fun TrendChart(v: List<Double>) {
    val line = L.text
    val base = L.border
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val mn = v.min(); val mx = v.max()
        val span = if (mx - mn > 0) mx - mn else 1.0
        val pad = 4.dp.toPx()
        val path = Path()
        v.forEachIndexed { i, y ->
            val px = size.width * i / (v.size - 1)
            val py = size.height - pad - ((y - mn) / span * (size.height - 2 * pad)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        drawPath(path, line, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
fun CashFlow(txns: List<Txn>, mode: Int, onMode: (Int) -> Unit) {
    val flows = remember(txns) { txns.filter { it.type != "TRANSFER" } }
    fun net(a: Long, b: Long) = flows.filter { it.date >= a && it.date < b }.sumOf { if (it.type == "INCOME") it.amount else -it.amount }
    val rows: List<Pair<String, Double>> = if (mode == 0) (0..6).map { k ->
        val d = LocalDate.now().minusDays(k.toLong())
        d.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) to net(dayMillis(d), dayMillis(d.plusDays(1)))
    } else (0..5).map { k ->
        val d = LocalDate.now().withDayOfMonth(1).minusMonths(k.toLong())
        d.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)) to net(dayMillis(d), dayMillis(d.plusMonths(1)))
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(24.dp, 24.dp, 24.dp, 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Cap("Cash flow")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Day", mode == 0) { onMode(0) }
                Chip("Month", mode == 1) { onMode(1) }
            }
        }
        Hairline()
        rows.forEach { (label, v) ->
            Row(Modifier.fillMaxWidth().padding(24.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = L.text, fontSize = 14.sp)
                Num(
                    if (v > 0.004) "+" + inr(v) else if (v < -0.004) "−" + inr(v) else inr(0.0), size = 14.sp,
                    color = if (v > 0.004) L.green else if (v < -0.004) L.red else L.muted
                )
            }
            Hairline()
        }
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
