package com.mtracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
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
    val flows = txns.filter { it.accountDelta() != 0.0 }.sortedBy { it.date }
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
            run += t.accountDelta()
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
    var period by remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    val peopleNames by vm.peopleNames.collectAsState()
    var flowMode by rememberSaveable { mutableIntStateOf(0) }
    val ranges = listOf("1M" to 1, "3M" to 3, "6M" to 6, "1Y" to 12, "All" to 0)

    val (lo, hi) = if (range == 5 && cTo != 0L) cFrom to cTo
    else if (range == 5) 0L to Long.MAX_VALUE
    else {
        val m = ranges[range].second
        (if (m == 0) 0L else dayMillis(LocalDate.now().minusMonths(m.toLong()))) to Long.MAX_VALUE
    }
    val ex = remember(txns, lo, hi) { txns.filter { it.type == "EXPENSE" && it.date in lo..hi } }
    val total = ex.sumOf { it.myAmount }
    val cats = remember(ex) { ex.groupBy { it.category }.map { it.key to it.value.sumOf { t -> t.myAmount } }.sortedByDescending { it.second } }
    val merchants = remember(ex) {
        ex.filter { it.merchant.isNotBlank() }.groupBy { it.merchant.trim() }
            .map { it.key to it.value.sumOf { t -> t.myAmount } }.sortedByDescending { it.second }
    }
    val tags = remember(ex) {
        ex.filter { it.tag.isNotBlank() }.groupBy { "#" + it.tag.trim() }
            .map { it.key to it.value.sumOf { t -> t.myAmount } }.sortedByDescending { it.second }
    }
    val series = remember(txns, accs, lo, hi) { trendSeries(txns, accs, lo, hi) }
    val marks by vm.dayMarks.collectAsState()
    val spendByDay = remember(txns) { txns.filter { it.type == "EXPENSE" }.groupBy { it.date }.mapValues { e -> e.value.sumOf { it.myAmount } } }
    val logged = remember(txns, marks) { txns.map { it.date }.toSet() + marks.map { it.date }.toSet() }

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
        item {
            Column(Modifier.padding(24.dp, 20.dp)) {
                Cap("Activity · last 16 weeks")
                Spacer(Modifier.height(12.dp))
                Heatmap(spendByDay, logged) { d -> period = Triple(dayLabel(d), d, dayMillis(java.time.Instant.ofEpochMilli(d).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1))) }
            }
            Hairline()
        }
        item { CashFlow(txns, flowMode, { flowMode = it }, { l, a, b -> period = Triple(l, a, b) }) }
        item { Ranked("Spend by head", cats, heads = true) }
        item { Ranked("Top merchants", merchants) }
        item { Ranked("Spend by tag", tags) }
        item { Spacer(Modifier.height(96.dp)) }
    }

    period?.let { PeriodSheet(it.first, it.second, it.third, vm) { period = null } }

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
fun CashFlow(txns: List<Txn>, mode: Int, onMode: (Int) -> Unit, onOpen: (String, Long, Long) -> Unit) {
    val flows = remember(txns) { txns.filter { it.type == "EXPENSE" || it.type == "INCOME" } }
    fun net(a: Long, b: Long) = flows.filter { it.date >= a && it.date < b }.sumOf { if (it.type == "INCOME") it.amount else -it.myAmount }
    val rows: List<Triple<String, Double, Pair<Long, Long>>> = if (mode == 0) (0..6).map { k ->
        val d = LocalDate.now().minusDays(k.toLong())
        val a = dayMillis(d); val b = dayMillis(d.plusDays(1))
        Triple(d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)), net(a, b), a to b)
    } else (0..5).map { k ->
        val d = LocalDate.now().withDayOfMonth(1).minusMonths(k.toLong())
        val a = dayMillis(d); val b = dayMillis(d.plusMonths(1))
        Triple(d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), net(a, b), a to b)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(24.dp, 24.dp, 24.dp, 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Cap("Cash flow · tap for details")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Day", mode == 0) { onMode(0) }
                Chip("Month", mode == 1) { onMode(1) }
            }
        }
        Hairline()
        rows.forEach { (label, v, range) ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(label, range.first, range.second) }.padding(24.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = L.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Num(
                    if (v > 0.004) "+" + inr(v) else if (v < -0.004) "−" + inr(v) else inr(0.0), size = 14.sp,
                    color = if (v > 0.004) L.green else if (v < -0.004) L.red else L.muted
                )
                Text("  ›", color = L.muted, fontSize = 18.sp)
            }
            Hairline()
        }
    }
}

/** Everything logged in a chosen day or month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSheet(label: String, from: Long, to: Long, vm: MainViewModel, onDismiss: () -> Unit) {
    val txns by vm.txns.collectAsState()
    val accs by vm.accounts.collectAsState()
    val people by vm.peopleNames.collectAsState()
    val names = remember(accs) { accs.associate { it.id to it.name } }
    val list = txns.filter { it.date >= from && it.date < to }
    val out = list.filter { it.type == "EXPENSE" }.sumOf { it.myAmount }
    val inc = list.filter { it.type == "INCOME" }.sumOf { it.amount }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = L.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Cap(label, color = L.text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Cap("Expense"); Spacer(Modifier.height(4.dp)); Num(inr(out), size = 18.sp) }
                Column { Cap("Income"); Spacer(Modifier.height(4.dp)); Num(inr(inc), size = 18.sp, color = L.green) }
                Column(horizontalAlignment = Alignment.End) { Cap("Net"); Spacer(Modifier.height(4.dp)); Num(inrS(inc - out), size = 18.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Hairline()
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (list.isEmpty()) item { Text("Nothing logged.", color = L.muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)) }
            items(list, key = { it.id }) { t -> PeriodLine(t, names, people) }
        }
    }
}

@Composable
private fun PeriodLine(t: Txn, names: Map<Long, String>, people: Map<Long, String>) {
    val who = people[t.personId]
    val title = when (t.type) {
        "TRANSFER" -> "Transfer"
        "PERSON_OUT" -> "Lent to ${who ?: "someone"}"
        "PERSON_IN" -> "Received from ${who ?: "someone"}"
        "PERSON_ADJ" -> "${who ?: "Someone"} paid for you"
        else -> t.note.ifBlank { t.merchant }.ifBlank { t.category }
    }
    val sub = listOfNotNull(
        dayLabel(t.date), t.merchant.takeIf { it.isNotBlank() && it != title },
        if (t.type.startsWith("PERSON") || t.type == "TRANSFER") null else t.category, names[t.accountId]
    ).joinToString(" · ")
    Column {
        Row(Modifier.fillMaxWidth().padding(24.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                t.type == "TRANSFER" -> HeadTile("Transfer", 40.dp, stored = "swap")
                t.type.startsWith("PERSON") -> HeadTile(who ?: "People", 40.dp, stored = "person")
                else -> HeadTile(t.category, 40.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = L.text, fontSize = 15.sp, maxLines = 1)
                Text(sub, color = L.muted, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            when (t.type) {
                "INCOME", "PERSON_IN" -> Num("+" + inr(t.amount), color = L.green)
                "EXPENSE", "PERSON_OUT" -> Num("−" + inr(t.amount))
                else -> Num(inr(t.amount), color = L.muted)
            }
        }
        Hairline(Modifier.padding(start = 74.dp))
    }
}

@Composable
fun Ranked(title: String, rows: List<Pair<String, Double>>, limit: Int = 8, heads: Boolean = false) {
    Column {
        Cap(title, Modifier.padding(24.dp, 28.dp, 24.dp, 8.dp))
        Hairline()
        if (rows.isEmpty()) Text("No data in this period", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 14.dp))
        val max = rows.maxOfOrNull { it.second } ?: 1.0
        rows.take(limit).forEach { (n, v) ->
            Column(Modifier.padding(24.dp, 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (heads) { HeadTile(n, 30.dp); Spacer(Modifier.width(12.dp)) }
                        Text(n, color = L.text, fontSize = 14.sp, maxLines = 1)
                    }
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

/** 16 weeks of days: filled = spent (darker = more), green dot = logged a no-spend day, outline = nothing logged. */
@Composable
fun Heatmap(spendByDay: Map<Long, Double>, logged: Set<Long>, onDay: (Long) -> Unit) {
    val weeks = 16
    val first = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    val today = LocalDate.now()
    val mx = spendByDay.values.maxOrNull() ?: 1.0
    val ink = L.text; val line = L.border; val green = L.green
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(132.dp).pointerInput(Unit) {
                detectTapGestures { o ->
                    val gap = 4.dp.toPx()
                    val cell = (size.width - gap * (weeks - 1)) / weeks
                    val c = (o.x / (cell + gap)).toInt(); val r = (o.y / (cell + gap)).toInt()
                    if (c in 0 until weeks && r in 0 until 7) {
                        val d = first.plusDays((c * 7 + r).toLong())
                        if (!d.isAfter(today)) onDay(dayMillis(d))
                    }
                }
            }
        ) {
            val gap = 4.dp.toPx()
            val cell = (size.width - gap * (weeks - 1)) / weeks
            for (c in 0 until weeks) for (r in 0 until 7) {
                val d = first.plusDays((c * 7 + r).toLong())
                if (d.isAfter(today)) continue
                val key = dayMillis(d)
                val x = c * (cell + gap); val y = r * (cell + gap)
                val sp = spendByDay[key]
                val radius = CornerRadius(3.dp.toPx())
                when {
                    sp != null && sp > 0 -> drawRoundRect(ink.copy(alpha = (0.2 + 0.8 * (sp / mx)).toFloat().coerceIn(0.2f, 1f)), Offset(x, y), Size(cell, cell), radius)
                    key in logged -> drawRoundRect(green.copy(alpha = 0.55f), Offset(x, y), Size(cell, cell), radius)
                    else -> drawRoundRect(line, Offset(x, y), Size(cell, cell), radius, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Tap a day to see it. Darker = spent more · green = no-spend day · outline = nothing logged", color = L.muted, fontSize = 11.sp)
    }
}
