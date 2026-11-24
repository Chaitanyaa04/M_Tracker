package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tiny arithmetic evaluator for the amount field: + - * / x ÷ − and parentheses. */
object Calc {
    fun eval(raw: String): Double? {
        val t = raw.replace("×", "*").replace("÷", "/").replace("−", "-").replace(" ", "").replace(",", "")
        if (t.isEmpty()) return null
        val p = P(t)
        return try {
            val v = p.expr()
            if (p.i != t.length || v.isNaN() || v.isInfinite()) null else v
        } catch (e: Exception) { null }
    }

    private class P(val s: String) {
        var i = 0
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
            return v
        }
        fun factor(): Double {
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '(') {
                i++; val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalStateException()
                i++; return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalStateException()
            return s.substring(st, i).toDouble()
        }
    }
}

private const val OPS = "+−×÷"

/** In-sheet keypad: no system keyboard needed for the amount, so logging is thumb-fast. */
@Composable
fun Keypad(onKey: (String) -> Unit) {
    val hf = LocalHapticFeedback.current
    val hapticsOn = LocalHaptics.current
    val keys = listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", ".", "0", "⌫", "+")
    Column {
        keys.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(50.dp).clickable { if (hapticsOn) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(k) }, contentAlignment = Alignment.Center) {
                        Text(k, color = if (k in "+−×÷⌫") L.muted else L.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryPickerDialog(
    cats: List<String>, onPick: (String) -> Unit, onAdd: (String) -> Unit, onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    val shown = cats.filter { q.isBlank() || it.contains(q.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(6.dp),
        title = { Cap("Select a category", color = L.text) },
        text = {
            Column {
                Field("Search or add new", q, { q = it })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(c) }) {
                            Text(c, color = L.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 13.dp))
                            Hairline()
                        }
                    }
                    if (q.isNotBlank() && cats.none { it.equals(q.trim(), true) }) item(key = "__add") {
                        Text("+ Add “${q.trim()}”", color = L.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clickable { onAdd(q.trim()) }.padding(vertical = 13.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionSheet(
    d: Draft, accounts: List<Account>, expenseCats: List<String>, incomeCats: List<String>,
    recents: List<Txn>, merchantNames: List<String>, lookup: (String) -> Txn?,
    onAddCategory: (String, String) -> Unit, onDismiss: () -> Unit, onSave: (Txn, String) -> Unit
) {
    val isNew = d.id == 0L
    var type by remember { mutableStateOf(d.type) }
    var amount by remember { mutableStateOf(d.amount) }
    var merchant by remember { mutableStateOf(d.merchant) }
    var note by remember { mutableStateOf(d.note) }
    var date by remember { mutableLongStateOf(d.date) }
    var category by remember { mutableStateOf(d.category) }
    var accId by remember { mutableLongStateOf(d.accountId) }
    var toId by remember { mutableLongStateOf(d.toAccountId) }
    var tag by remember { mutableStateOf(d.tag) }
    var exclude by remember { mutableStateOf(d.exclude) }
    var repeat by remember { mutableStateOf(d.repeat) }
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(!isNew) }
    var catTouched by remember { mutableStateOf(!isNew) }
    var accTouched by remember { mutableStateOf(!isNew || d.pendingId != 0L) }
    val zone = ZoneId.systemDefault()

    LaunchedEffect(accounts) { if (accId == 0L && accounts.isNotEmpty()) accId = accounts.first().id }
    // Coming from a payment prompt with a known merchant? Pre-fill from history.
    LaunchedEffect(Unit) {
        if (isNew && d.merchant.isNotBlank()) lookup(d.merchant)?.let { if (it.type == type) { category = it.category; if (tag.isBlank()) tag = it.tag } }
    }

    val cats = if (type == "INCOME") incomeCats else expenseCats
    val preview = remember(amount) { if (amount.any { it in OPS }) Calc.eval(amount) else null }
    val accOptions = accounts.map { it.name to it.id }
    val accName = { id: Long -> accounts.firstOrNull { it.id == id }?.name ?: "Select" }

    fun fill(t: Txn) {
        merchant = t.merchant; amount = fmtAmt(t.amount); category = t.category
        accId = t.accountId; tag = t.tag; catTouched = true; accTouched = true
    }
    fun onMerchant(s: String) {
        merchant = s
        if (isNew) lookup(s)?.let {
            if (it.type == type) {
                if (!catTouched) category = it.category
                if (!accTouched) accId = it.accountId
                if (tag.isBlank()) tag = it.tag
            }
        }
    }
    fun press(k: String) {
        when (k) {
            "⌫" -> amount = amount.dropLast(1)
            "+", "−", "×", "÷" -> {
                if (amount.isNotEmpty()) amount = if (amount.last() in OPS) amount.dropLast(1) + k else amount + k
            }
            "." -> {
                val seg = amount.takeLastWhile { it !in OPS }
                if (!seg.contains('.')) amount += if (seg.isEmpty()) "0." else "."
            }
            else -> amount += k
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = L.surface,
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
        dragHandle = null
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Cap(if (isNew) "New entry" else "Edit entry", color = L.text)

            val shape = RoundedCornerShape(6.dp)
            Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, L.border, shape)) {
                listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (k, label) ->
                    val sel = type == k
                    Box(
                        Modifier.weight(1f).background(if (sel) L.accent else Color.Transparent)
                            .clickable {
                                type = k
                                val list = if (k == "INCOME") incomeCats else expenseCats
                                if (category !in list) category = if ("Other" in list) "Other" else list.firstOrNull() ?: "Other"
                            }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = if (sel) L.onAccent else L.text, fontSize = 13.sp) }
                }
            }

            // Amount display + keypad
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Cap("Amount (INR)")
                    Spacer(Modifier.height(4.dp))
                    Num(if (amount.isEmpty()) "0" else amount, size = 40.sp, weight = FontWeight.Light,
                        color = if (amount.isEmpty()) L.muted else L.text)
                    if (preview != null) Num("= " + fmtAmt(round2(preview)), size = 14.sp, color = L.muted)
                }
                if (amount.isNotEmpty()) Cap("Clear", Modifier.clickable { amount = "" }.padding(8.dp), color = L.text)
            }
            Hairline()

            // One-tap repeats
            val chips = if (isNew) recents.filter { it.type == type }.take(6) else emptyList()
            if (chips.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cap("Repeat a recent one")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { t -> Chip(t.merchant + " · " + fmtAmt(t.amount), false) { fill(t) } }
                    }
                }
            }

            Keypad(::press)

            Column {
                Field("Merchant", merchant, ::onMerchant)
                val matches = if (isNew && merchant.isNotBlank())
                    merchantNames.filter { it.contains(merchant.trim(), true) && !it.equals(merchant.trim(), true) }.take(4)
                else emptyList()
                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        matches.forEach { m -> Chip(m, false) { onMerchant(m) } }
                    }
                }
            }

            if (type != "TRANSFER") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).clickable { showCats = true }) {
                        Cap("Category")
                        Text(category, color = L.text, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                        Hairline()
                    }
                    Picker("Account", accName(accId), accOptions, { accId = it; accTouched = true }, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Picker("From", accName(accId), accOptions, { accId = it }, Modifier.weight(1f))
                    Picker("To", accName(toId), accOptions, { toId = it }, Modifier.weight(1f))
                }
            }

            Cap(if (showMore) "Hide details" else "More details", Modifier.clickable { showMore = !showMore }.padding(vertical = 4.dp), color = L.text)
            if (showMore) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.clickable { showDate = true }) {
                        Cap("Date")
                        Text(
                            Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
                                .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)),
                            color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        )
                        Hairline()
                    }
                    Field("Note", note, { note = it })
                    if (type != "TRANSFER") Field("Tag (e.g. personal)", tag, { tag = it.replace("#", "") })
                    if (type == "EXPENSE") SwitchRow("Exclude from monthly budget", exclude) { exclude = it }
                    if (isNew) Picker(
                        "Repeat",
                        listOf("NEVER" to "Never", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly")
                            .first { it.first == repeat }.second,
                        listOf("Never" to "NEVER", "Daily" to "DAILY", "Weekly" to "WEEKLY", "Monthly" to "MONTHLY", "Yearly" to "YEARLY"),
                        { repeat = it }
                    )
                }
            }

            if (error.isNotEmpty()) Text(error, color = L.red, fontSize = 13.sp)

            MonoButton("Save", {
                val v = Calc.eval(amount)
                when {
                    v == null || v <= 0 -> error = "Enter a valid amount"
                    accId == 0L -> error = "Pick an account"
                    type == "TRANSFER" && (toId == 0L || toId == accId) -> error = "Pick two different accounts"
                    else -> onSave(
                        Txn(
                            id = d.id, type = type, amount = round2(v),
                            merchant = merchant.trim(), note = note.trim(), date = date,
                            category = if (type == "TRANSFER") "Transfer" else category,
                            accountId = accId, toAccountId = if (type == "TRANSFER") toId else null,
                            tag = tag.trim(), excludeFromBudget = exclude && type == "EXPENSE"
                        ),
                        if (isNew) repeat else "NEVER"
                    )
                }
            }, Modifier.fillMaxWidth())
        }
    }

    if (showCats) CategoryPickerDialog(
        cats = cats,
        onPick = { category = it; catTouched = true; showCats = false },
        onAdd = { n ->
            onAddCategory(n, if (type == "INCOME") "INCOME" else "EXPENSE")
            category = n; catTouched = true; showCats = false
        },
        onDismiss = { showCats = false }
    )

    if (showDate) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton({ st.selectedDateMillis?.let { date = pickerToLocal(it) }; showDate = false }) { Text("OK") }
            },
            dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
}
