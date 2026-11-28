package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ------------------------------------------------------------------ itemised bills
data class LineItem(val name: String = "", val price: String = "")
/** sign: -1 = discount, +1 = extra charge (tax, delivery…). amount may be "10%" for discounts. */
data class Adj(val label: String = "", val amount: String = "", val sign: Int = -1)

fun adjValue(a: Adj, subtotal: Double): Double {
    val t = a.amount.trim()
    val v = if (t.endsWith("%")) subtotal * (t.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0 else t.toDoubleOrNull() ?: 0.0
    return a.sign * v
}

fun itemsJson(items: List<LineItem>, adjs: List<Adj>, subtotal: Double): String {
    val ia = JSONArray()
    items.filter { it.name.isNotBlank() || (it.price.toDoubleOrNull() ?: 0.0) > 0 }
        .forEach { ia.put(JSONArray().put(it.name.trim()).put(it.price.toDoubleOrNull() ?: 0.0)) }
    val aa = JSONArray()
    adjs.forEach { val v = adjValue(it, subtotal); if (v != 0.0) aa.put(JSONArray().put(it.label.trim()).put(v)) }
    return if (ia.length() == 0) "" else JSONObject().put("i", ia).put("a", aa).toString()
}

fun parseItems(json: String): Pair<List<LineItem>, List<Adj>> = try {
    val o = JSONObject(json)
    val i = o.getJSONArray("i"); val a = o.getJSONArray("a")
    (0 until i.length()).map { i.getJSONArray(it) }.map { LineItem(it.getString(0), fmtAmt(it.getDouble(1))) } to
        (0 until a.length()).map { a.getJSONArray(it) }.map { Adj(it.getString(0), fmtAmt(kotlin.math.abs(it.getDouble(1))), if (it.getDouble(1) < 0) -1 else 1) }
} catch (e: Exception) { emptyList<LineItem>() to emptyList<Adj>() }

fun itemCount(json: String): Int = if (json.isBlank()) 0 else parseItems(json).first.size

private fun cleanAmount(s: String): String {
    var dot = false
    val sb = StringBuilder()
    for (ch in s) if (ch.isDigit()) sb.append(ch) else if (ch == '.' && !dot) { dot = true; sb.append(ch) }
    val t = sb.toString(); val i = t.indexOf('.')
    return if (i >= 0 && t.length - i > 3) t.substring(0, i + 3) else t
}

@Composable
private fun Quiet(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    kb: KeyboardType = KeyboardType.Text, right: Boolean = false, size: Int = 15
) {
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = L.text, fontSize = size.sp, fontFeatureSettings = "tnum", textAlign = if (right) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start),
        cursorBrush = SolidColor(L.text),
        keyboardOptions = KeyboardOptions(keyboardType = kb, imeAction = ImeAction.Next),
        modifier = modifier,
        decorationBox = { inner ->
            Box(contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = L.muted.copy(alpha = 0.6f), fontSize = size.sp)
                inner()
            }
        }
    )
}

/** Receipt-style breakdown: items, discounts / charges, and a live total. */
@Composable
private fun Receipt(items: MutableList<LineItem>, adjs: MutableList<Adj>, subtotal: Double, total: Double) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(L.bg).border(1.dp, L.border, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Receipt, null, tint = L.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp)); Cap("Your bill")
        }
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { i, it ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("•", color = L.muted, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                Quiet(it.name, { v -> items[i] = it.copy(name = v) }, "Item", Modifier.weight(1f))
                Quiet(it.price, { v -> items[i] = it.copy(price = cleanAmount(v)) }, "0.00", Modifier.width(84.dp), KeyboardType.Decimal, right = true)
                Icon(Icons.Rounded.Close, "Remove", tint = L.muted, modifier = Modifier.padding(start = 10.dp).size(18.dp).clickable { if (items.size > 1) items.removeAt(i) else items[0] = LineItem() })
            }
            Hairline()
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("+ Item", false) { items.add(LineItem()) }
            Chip("+ Discount", false) { adjs.add(Adj("Discount", "", -1)) }
            Chip("+ Tax / delivery", false) { adjs.add(Adj("Delivery", "", 1)) }
        }
        if (adjs.isNotEmpty()) Spacer(Modifier.height(8.dp))
        adjs.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (a.sign < 0) "−" else "+", color = if (a.sign < 0) L.green else L.muted, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                Quiet(a.label, { v -> adjs[i] = a.copy(label = v) }, if (a.sign < 0) "Discount" else "Charge", Modifier.weight(1f))
                Quiet(a.amount, { v -> adjs[i] = a.copy(amount = v.filter { c -> c.isDigit() || c == '.' || c == '%' }.take(9)) }, if (a.sign < 0) "₹ or 10%" else "0.00", Modifier.width(96.dp), right = true)
                Icon(Icons.Rounded.Close, "Remove", tint = L.muted, modifier = Modifier.padding(start = 10.dp).size(18.dp).clickable { adjs.removeAt(i) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Subtotal", color = L.muted, fontSize = 13.sp); Num(inr(subtotal), size = 13.sp, color = L.muted)
        }
        adjs.forEach { a ->
            val v = adjValue(a, subtotal)
            if (v != 0.0) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(a.label.ifBlank { if (a.sign < 0) "Discount" else "Charge" }, color = L.muted, fontSize = 13.sp)
                Num((if (v < 0) "−" else "+") + inr(v), size = 13.sp, color = if (v < 0) L.green else L.muted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", color = L.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Num(inr(total), size = 18.sp, weight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionSheet(
    d: Draft, accounts: List<Account>, heads: List<Category>, usage: Map<String, Int>, likely: (String) -> List<String>,
    recents: List<Txn>, merchantNames: List<String>, lookup: (String) -> Txn?,
    people: List<Person>, trips: List<Trip>, lastTrip: () -> Txn?, places: () -> List<String>, routes: () -> List<Pair<String, String>>,
    onAddPerson: (String, (Long) -> Unit) -> Unit,
    onAddHead: (String, String, String) -> Unit, onDelete: (Txn) -> Unit, onDismiss: () -> Unit, onSave: (Txn, String) -> Unit
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
    var routeFrom by remember { mutableStateOf(d.routeFrom) }
    var routeTo by remember { mutableStateOf(d.routeTo) }
    var editingTo by remember { mutableStateOf(false) }
    var personId by remember { mutableLongStateOf(d.personId) }
    var tripId by remember { mutableLongStateOf(d.tripId) }
    var split by remember { mutableStateOf(d.split) }
    var share by remember { mutableStateOf(d.share) }
    var shareTouched by remember { mutableStateOf(d.share.isNotBlank()) }
    var addingPerson by remember { mutableStateOf(false) }
    val parsed = remember { if (d.items.isNotBlank()) parseItems(d.items) else emptyList<LineItem>() to emptyList<Adj>() }
    var itemized by remember { mutableStateOf(d.items.isNotBlank()) }
    val items = remember { mutableStateListOf<LineItem>().also { it.addAll(parsed.first.ifEmpty { listOf(LineItem(), LineItem()) }) } }
    val adjs = remember { mutableStateListOf<Adj>().also { it.addAll(parsed.second) } }
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var showHeads by remember { mutableStateOf(false) }
    var newHead by remember { mutableStateOf(false) }
    var showMerchants by remember { mutableStateOf(false) }
    var newMerchant by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(!isNew) }
    var catTouched by remember { mutableStateOf(!isNew) }
    var accTouched by remember { mutableStateOf(!isNew || d.pendingId != 0L) }
    val zone = ZoneId.systemDefault()
    val focus = remember { FocusRequester() }

    // Keep an already-selected archived account visible while editing an old transaction,
    // so saving a note never silently moves money to a different account.
    val activeAccounts = accounts.filter { !it.archived || it.id == accId || it.id == toId }
    LaunchedEffect(activeAccounts) { if (accId == 0L && activeAccounts.isNotEmpty()) accId = activeAccounts.first().id }
    LaunchedEffect(Unit) {
        if (isNew && d.merchant.isNotBlank()) lookup(d.merchant)?.let { if (it.type == type) { category = it.category; if (tag.isBlank()) tag = it.tag } }
        if (isNew && d.amount.isBlank()) { delay(300); runCatching { focus.requestFocus() } }   // opens the normal number pad
    }

    val kind = if (type == "INCOME") "INCOME" else "EXPENSE"
    val headNames = heads.filter { it.kind == kind }.map { it.name }
    val order = if (kind == "INCOME") DEFAULT_INCOME_CATEGORIES else DEFAULT_EXPENSE_CATEGORIES
    val baseQuick = remember(kind, heads, usage) {
        val l = likely(kind).filter { it in headNames }
        val byUse = headNames.sortedWith(
            compareByDescending<String> { usage["$kind|$it"] ?: 0 }.thenBy { order.indexOf(it).let { i -> if (i < 0) 999 else i } }
        )
        (l + byUse).distinct()
    }
    val quick = ((if (category in headNames) listOf(category) else emptyList()) + baseQuick).distinct().take(4)
    val topMerchants = remember(merchantNames) { merchantNames.take(12) }
    val merchantRow = ((if (merchant.isNotBlank()) listOf(merchant.trim()) else emptyList()) + topMerchants).distinct().take(4)
    fun accLabel(a: Account) = if (a.archived) "${a.name} (archived)" else a.name
    val accOptions = activeAccounts.map { accLabel(it) to it.id }
    val accName = { id: Long -> activeAccounts.firstOrNull { it.id == id }?.let { a -> accLabel(a) } ?: "Select" }
    val iconKey = (LocalHeadIcons.current["$type|$category"] ?: LocalHeadIcons.current[category])
        ?.takeIf { it in HEAD_ICON_MAP } ?: guessIconKey(category)
    val showTrip = type == "EXPENSE" && iconKey in setOf("car", "bus", "flight")

    val subtotal = items.sumOf { it.price.toDoubleOrNull() ?: 0.0 }
    val itemTotal = round2(subtotal + adjs.sumOf { adjValue(it, subtotal) })
    val totalValue: Double? = if (itemized) itemTotal else amount.toDoubleOrNull()
    val half = round2((totalValue ?: 0.0) / 2)
    val shareValue = if (shareTouched) share.toDoubleOrNull() ?: 0.0 else half
    val personName = people.firstOrNull { it.id == personId }?.name ?: ""
    val signColor = when (type) { "EXPENSE" -> L.red; "INCOME" -> L.green; else -> L.muted }
    val signText = when (type) { "EXPENSE" -> "−"; "INCOME" -> "+"; else -> "" }

    fun fill(t: Txn) {
        merchant = t.merchant; note = t.note; amount = fmtAmt(t.amount); category = t.category
        accId = t.accountId; tag = t.tag; catTouched = true; accTouched = true
    }
    fun onMerchant(s: String) {
        merchant = s
        if (isNew && s.isNotBlank()) lookup(s)?.let {
            if (it.type == type) {
                if (!catTouched) category = it.category
                if (!accTouched) accId = it.accountId
                if (tag.isBlank()) tag = it.tag
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = L.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).width(36.dp).height(4.dp).clip(CircleShape).background(L.border)) }
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isNew && d.original != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = {
                        d.original?.let(onDelete)
                        onDismiss()
                    }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete transaction", tint = L.red) }
                }
            }
            // ---- what kind of entry: plain words, no + / − guessing
            val shape = RoundedCornerShape(16.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(L.bg).border(1.dp, L.border, shape).padding(4.dp)) {
                listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (k, label) ->
                    val sel = type == k
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) L.accent else Color.Transparent)
                            .clickable {
                                type = k
                                val nk = if (k == "INCOME") "INCOME" else "EXPENSE"
                                val names = heads.filter { it.kind == nk }.map { it.name }
                                if (category !in names) category = ""
                            }.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = if (sel) L.onAccent else L.text, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal) }
                }
            }
            if (type == "TRANSFER") Text("Between your own accounts. Not counted as spending.", color = L.muted, fontSize = 12.sp)

            // ---- amount: the normal number pad
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cap(if (itemized) "Bill total" else "Amount"); Spacer(Modifier.weight(1f))
                    if (type == "EXPENSE") Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { itemized = !itemized }
                            .background(if (itemized) L.accent else L.bg).border(1.dp, L.border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Receipt, null, tint = if (itemized) L.onAccent else L.text, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (itemized) "Itemised" else "Itemise", color = if (itemized) L.onAccent else L.text, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(signText + "₹", color = signColor, fontSize = 40.sp, fontWeight = FontWeight.Light)
                    if (itemized) Num(fmtAmt(itemTotal), size = 48.sp, weight = FontWeight.Light)
                    else BasicTextField(
                        amount, { amount = cleanAmount(it) }, singleLine = true,
                        textStyle = TextStyle(color = L.text, fontSize = 48.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
                        cursorBrush = SolidColor(L.text),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner -> Box { if (amount.isEmpty()) Text("0", color = L.muted.copy(alpha = 0.5f), fontSize = 48.sp, fontWeight = FontWeight.Light); inner() } }
                    )
                }
                Hairline()
            }

            Field("Title", note, { note = it })

            if (itemized) Receipt(items, adjs, subtotal, itemTotal)

            val chips = if (isNew) recents.filter { it.type == type }.take(6) else emptyList()
            if (chips.isNotEmpty() && amount.isEmpty() && !itemized) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cap("Recent")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { t -> Chip(t.note.ifBlank { t.merchant } + " · " + fmtAmt(t.amount), false) { fill(t) } }
                    }
                }
            }

            if (type != "TRANSFER") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Cap("Head")
                        Cap(category.ifBlank { "Required" }, color = if (category.isBlank()) L.red else L.text)
                    }
                    HeadStrip(quick, category, onPick = { category = it; catTouched = true }, onNew = { newHead = true }, onMore = { showHeads = true })
                }
            }

            // ---- trip template
            if (showTrip) {
                val last = remember { lastTrip() }
                val usual = remember { routes() }
                val known = remember { places() }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cap("Trip")
                    if (isNew && last != null && routeFrom.isBlank() && routeTo.isBlank()) {
                        Text("Your last trip was ${last.routeFrom} → ${last.routeTo}. Is this…", color = L.muted, fontSize = 13.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("↩ Return trip", false) { routeFrom = last.routeTo; routeTo = last.routeFrom }
                            Chip("→ Continuing from ${last.routeTo}", false) { routeFrom = last.routeTo; editingTo = true }
                            Chip("Something new", false) { editingTo = false }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Field("From", routeFrom, { routeFrom = it; editingTo = false }, Modifier.weight(1f))
                        Field("To", routeTo, { routeTo = it; editingTo = true }, Modifier.weight(1f))
                    }
                    val typed = if (editingTo) routeTo else routeFrom
                    val sugg = known.filter { typed.isNotBlank() && it.contains(typed.trim(), true) && !it.equals(typed.trim(), true) }.take(5)
                    if (sugg.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sugg.forEach { p -> Chip(p, false) { if (editingTo) routeTo = p else routeFrom = p } }
                    } else if (routeFrom.isBlank() && routeTo.isBlank() && usual.isNotEmpty()) {
                        Cap("Usual routes")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            usual.forEach { (a, b) -> Chip("$a → $b", false) { routeFrom = a; routeTo = b } }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Cap("Merchant")
                    Cap(merchant.ifBlank { "Optional" }, color = if (merchant.isBlank()) L.muted else L.text)
                }
                if (newMerchant) {
                    Field("New merchant", merchant, ::onMerchant)
                    val matches = if (isNew && merchant.isNotBlank())
                        merchantNames.filter { it.contains(merchant.trim(), true) && !it.equals(merchant.trim(), true) }.take(4) else emptyList()
                    if (matches.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        matches.forEach { m -> Chip(m, false) { onMerchant(m); newMerchant = false } }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (merchantRow.isEmpty()) Text("No merchants yet", color = L.muted, fontSize = 13.sp)
                        merchantRow.forEach { m ->
                            val on = m.equals(merchant.trim(), true)
                            Chip(m, on) { if (on) onMerchant("") else onMerchant(m) }
                        }
                    }
                    PinnedPair({ newMerchant = true }, { showMerchants = true }, 44.dp)
                }
            }

            // ---- people & splits
            if (type == "EXPENSE") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cap("Who was this for?")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Just me", personId == 0L) { personId = 0 }
                        people.forEach { p -> Chip(p.name, personId == p.id) { personId = p.id } }
                        Chip("+ Person", false) { addingPerson = true }
                    }
                    if (personId != 0L) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Just note", split == "TAG") { split = "TAG" }
                            Chip("Split", split == "SPLIT") { split = "SPLIT" }
                            Chip("I covered all", split == "FULL") { split = "FULL" }
                        }
                        val tv = totalValue ?: 0.0
                        Text(
                            when (split) {
                                "SPLIT" -> "$personName owes you ${inr(shareValue)}"
                                "FULL" -> "$personName owes you ${inr(tv)}"
                                else -> "Noted only. Nothing owed."
                            },
                            color = L.muted, fontSize = 12.sp, lineHeight = 17.sp
                        )
                        if (split == "SPLIT") Field(
                            "$personName’s share", if (shareTouched) share else fmtAmt(half),
                            { share = cleanAmount(it); shareTouched = true }, kb = KeyboardType.Decimal
                        )
                    }
                }
            }

            if (type != "TRANSFER") Picker("Account", accName(accId), accOptions, { accId = it; accTouched = true })
            else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Picker("From", accName(accId), accOptions, { accId = it }, Modifier.weight(1f))
                Picker("To", accName(toId), accOptions, { toId = it }, Modifier.weight(1f))
            }

            Cap(if (showMore) "Hide details" else "More details", Modifier.clickable { showMore = !showMore }.padding(vertical = 4.dp), color = L.text)
            if (showMore) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.clickable { showDate = true }) {
                        Cap("Date")
                        Text(
                            Instant.ofEpochMilli(date).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)),
                            color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        )
                        Hairline()
                    }
                    if (type != "TRANSFER") Field("Tag (e.g. personal)", tag, { tag = it.replace("#", "") })
                    if (type != "TRANSFER" && trips.isNotEmpty()) Picker(
                        "Trip (optional)",
                        trips.firstOrNull { it.id == tripId }?.name ?: "Not part of a trip",
                        listOf("Not part of a trip" to 0L) + trips.map { it.name to it.id },
                        { tripId = it }
                    )
                    if (type == "EXPENSE") SwitchRow("Leave out of monthly budget", exclude) { exclude = it }
                    if (isNew) Picker(
                        "Repeat",
                        listOf("NEVER" to "Never", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly").first { it.first == repeat }.second,
                        listOf("Never" to "NEVER", "Daily" to "DAILY", "Weekly" to "WEEKLY", "Monthly" to "MONTHLY", "Yearly" to "YEARLY"),
                        { repeat = it }
                    )
                }
            }

            if (error.isNotEmpty()) Text(error, color = L.red, fontSize = 13.sp)

            MonoButton(if (totalValue != null && totalValue > 0) "Save · ${inr(totalValue)}" else "Save", {
                val v = totalValue
                when {
                    v == null || v <= 0 -> error = "Enter an amount first"
                    type != "TRANSFER" && category.isBlank() -> error = "Choose a head"
                    accId == 0L -> error = "Pick an account"
                    type == "TRANSFER" && (toId == 0L || toId == accId) -> error = "Pick two different accounts"
                    else -> {
                        val keptOwed = if (d.personId == 0L) d.share.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0 else 0.0
                        val owed = if (type == "EXPENSE") {
                            if (personId != 0L) when (split) {
                                "SPLIT" -> minOf(shareValue, v); "FULL" -> v; else -> 0.0
                            } else minOf(keptOwed, v)
                        } else 0.0
                        onSave(
                            Txn(
                                id = d.id, type = type, amount = round2(v),
                                merchant = merchant.trim(), note = note.trim(), date = date,
                                category = if (type == "TRANSFER") "Transfer" else category,
                                accountId = accId, toAccountId = if (type == "TRANSFER") toId else null,
                                tag = tag.trim(), excludeFromBudget = exclude && type == "EXPENSE",
                                personId = if (type == "EXPENSE") personId else 0, owed = round2(owed),
                                routeFrom = if (showTrip) routeFrom.trim() else "", routeTo = if (showTrip) routeTo.trim() else "",
                                items = if (itemized && type == "EXPENSE") itemsJson(items, adjs, subtotal) else "",
                                tripId = if (type == "TRANSFER") 0L else tripId,
                                createdAt = d.createdAt
                            ),
                            if (isNew) repeat else "NEVER"
                        )
                    }
                }
            }, Modifier.fillMaxWidth())
        }
    }

    if (addingPerson) {
        var nm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingPerson = false }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap("Add a person", color = L.text) },
            text = { Field("Name", nm, { nm = it.take(100) }) },
            confirmButton = { TextButton({ if (nm.isNotBlank()) { onAddPerson(nm.trim()) { id -> personId = id; split = "TAG" }; addingPerson = false } }) { Text("Add") } },
            dismissButton = { TextButton({ addingPerson = false }) { Text("Cancel") } }
        )
    }

    if (showHeads) HeadsSheet(
        heads = heads, initialKind = kind, selected = category, usage = usage, likely = likely, showKindSwitch = false,
        onPick = { n, _ -> category = n; catTouched = true; showHeads = false },
        onCreate = { n, k, ic -> onAddHead(n, k, ic); category = n; catTouched = true; showHeads = false },
        onDismiss = { showHeads = false }
    )

    if (newHead) NewHeadDialog(kind, { n, k, ic -> onAddHead(n, k, ic); category = n; catTouched = true; newHead = false }, { newHead = false })
    if (showMerchants) MerchantsSheet(merchantNames, merchant, { onMerchant(it); newMerchant = false; showMerchants = false }, { showMerchants = false })

    if (showDate) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(date).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { date = pickerToLocal(it) }; showDate = false }) { Text("OK") } },
            dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }
}
