package com.minimal.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionSheet(d: Draft, accounts: List<Account>, onDismiss: () -> Unit, onSave: (Txn) -> Unit) {
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
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    LaunchedEffect(accounts) { if (accId == 0L && accounts.isNotEmpty()) accId = accounts.first().id }

    val cats = if (type == "INCOME") INCOME_CATEGORIES else CATEGORIES
    val preview = remember(amount) { if (amount.any { it in "+-*/×÷−" }) Calc.eval(amount) else null }
    val accOptions = accounts.map { it.name to it.id }
    val accName = { id: Long -> accounts.firstOrNull { it.id == id }?.name ?: "Select" }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Cap(if (d.id == 0L) "New entry" else "Edit entry", color = L.text)

            // Segmented type toggle
            val shape = RoundedCornerShape(6.dp)
            Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, L.border, shape)) {
                listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (k, label) ->
                    val sel = type == k
                    Box(
                        Modifier.weight(1f).background(if (sel) L.accent else Color.Transparent)
                            .clickable {
                                type = k
                                val list = if (k == "INCOME") INCOME_CATEGORIES else CATEGORIES
                                if (category !in list) category = list.last()
                            }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = if (sel) L.onAccent else L.text, fontSize = 13.sp) }
                }
            }

            Column {
                Field("Amount (INR)", amount, { amount = it }, kb = KeyboardType.Decimal, size = 36.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("+", "−", "×", "÷").forEach { op -> Chip(op, false) { amount += op } }
                    Spacer(Modifier.weight(1f))
                    if (preview != null) Num("= " + fmtAmt(round2(preview)), color = L.muted)
                }
            }

            Field("Merchant", merchant, { merchant = it })
            Field("Note", note, { note = it })

            Column(Modifier.clickable { showDate = true }) {
                Cap("Date")
                Text(
                    Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
                        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)),
                    color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                )
                Hairline()
            }

            if (type != "TRANSFER") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Picker("Category", category, cats.map { it to it }, { category = it }, Modifier.weight(1f))
                    Picker("Account", accName(accId), accOptions, { accId = it }, Modifier.weight(1f))
                }
                Field("Tag (e.g. personal)", tag, { tag = it.replace("#", "") })
                if (type == "EXPENSE") SwitchRow("Exclude from monthly budget", exclude) { exclude = it }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Picker("From", accName(accId), accOptions, { accId = it }, Modifier.weight(1f))
                    Picker("To", accName(toId), accOptions, { toId = it }, Modifier.weight(1f))
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
                        )
                    )
                }
            }, Modifier.fillMaxWidth())
        }
    }

    if (showDate) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
}
