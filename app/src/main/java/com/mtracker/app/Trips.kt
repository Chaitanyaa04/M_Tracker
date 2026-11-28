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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** personId 0 is you; every other id refers to a Person row. */
private fun tripWho(people: List<Person>, id: Long) =
    if (id == 0L) "You" else people.firstOrNull { it.id == id }?.name ?: "Removed person"

private fun tripDate(start: Long, end: Long) =
    if (start == end) dayLabel(start) else "${dayLabel(start)} → ${dayLabel(end)}"

@Composable
fun TripsView(vm: MainViewModel, modifier: Modifier = Modifier) {
    val trips by vm.trips.collectAsState()
    val members by vm.tripMembers.collectAsState()
    val debts by vm.tripDebts.collectAsState()
    val txns by vm.txns.collectAsState()
    val people by vm.persons.collectAsState()
    val allAccs by vm.accounts.collectAsState()
    val accs = remember(allAccs) { allAccs.filter { !it.archived } }
    val hide by vm.prefs.hideBalances.collectAsState()

    var openId by rememberSaveable { mutableLongStateOf(0L) }
    var showArchived by remember { mutableStateOf(false) }
    var editorId by remember { mutableStateOf<Long?>(null) }
    var editorError by remember { mutableStateOf("") }
    var expenseFor by remember { mutableLongStateOf(0L) }
    var expenseError by remember { mutableStateOf("") }
    var settleTarget by remember { mutableStateOf<TripDebt?>(null) }
    var settleError by remember { mutableStateOf("") }
    var archiveId by remember { mutableLongStateOf(0L) }
    var deleteId by remember { mutableLongStateOf(0L) }

    fun m(s: String) = if (hide) "••••" else s
    fun balance(tripId: Long): Pair<Double, Double> {
        val open = debts.filter { it.tripId == tripId && !it.settled }
        return open.filter { it.toPersonId == 0L }.sumOf { it.amount } to open.filter { it.fromPersonId == 0L }.sumOf { it.amount }
    }
    @Composable
    fun yourLine(tripId: Long): Pair<String, androidx.compose.ui.graphics.Color> {
        val (gets, owes) = balance(tripId)
        return when {
            gets > 0.004 && owes > 0.004 -> "gets ${inr(gets)} · owes ${inr(owes)}" to L.text
            gets > 0.004 -> "you get ${inr(gets)}" to L.green
            owes > 0.004 -> "you owe ${inr(owes)}" to L.red
            else -> "all settled" to L.muted
        }
    }
    // Split rows are already counted in Trip.spent; ordinary linked expenses are not.
    fun spentOn(t: Trip): Double {
        val extra = txns.filter {
            it.tripId == t.id && it.type == "EXPENSE" && it.accountId != 0L &&
                it.tag != "trip" && !it.note.startsWith("Trip · ")
        }.sumOf { it.amount }
        return round2(t.spent + extra)
    }

    val active = remember(trips) { trips.filter { !it.archived } }
    val archived = remember(trips) { trips.filter { it.archived } }
    val openTrip = active.firstOrNull { it.id == openId }

    if (openTrip == null) {
        val allGets = active.sumOf { balance(it.id).first }
        val allOwes = active.sumOf { balance(it.id).second }
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp, 0.dp, 8.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Cap("You’ll get"); Spacer(Modifier.height(6.dp)); Num(m(inr(allGets)), size = 30.sp, weight = FontWeight.Light, color = L.green) }
                    Column { Cap("You owe"); Spacer(Modifier.height(6.dp)); Num(m(inr(allOwes)), size = 30.sp, weight = FontWeight.Light, color = if (allOwes > 0) L.red else L.text) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("+ New trip", { editorId = 0L }, Modifier.weight(1f))
                    Chip("Archived", showArchived) { showArchived = !showArchived }
                }
            }
            if (active.isEmpty()) item {
                Text("Trips keep everyone’s expenses and who owes whom in one place, even when nobody in the group uses this phone.",
                    color = L.muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(8.dp, 4.dp))
            }
            items(active, key = { it.id }) { t ->
                val (line, tone) = yourLine(t.id)
                val memberCount = members.count { it.tripId == t.id }
                val openCount = debts.count { it.tripId == t.id && !it.settled }
                val spent = spentOn(t)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(L.surface)
                        .border(1.dp, L.border, RoundedCornerShape(20.dp))
                        .clickable { openId = t.id }.padding(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.name, color = L.text, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Num(m(line), size = 13.sp, color = tone)
                    }
                    if (t.place.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(t.place, color = L.muted, fontSize = 12.sp, maxLines = 1) }
                    Spacer(Modifier.height(6.dp))
                    Text(tripDate(t.startDate, t.endDate), color = L.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    if (t.budget > 0) {
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(L.border)) {
                            Box(Modifier.fillMaxWidth((spent / t.budget).coerceIn(0.0, 1.0).toFloat()).height(5.dp).background(if (spent > t.budget) L.red else L.accent))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("${if (t.budget > 0) "Spent ${m(inr(spent))} of ${m(inr(t.budget))} · " else "Spent ${m(inr(spent))} · "}$memberCount people · $openCount open",
                        color = L.muted, fontSize = 11.sp, maxLines = 1)
                }
            }
            if (showArchived && archived.isNotEmpty()) item {
                Column {
                    Cap("Archived", Modifier.padding(8.dp, 6.dp))
                    archived.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, color = L.text, fontSize = 14.sp, maxLines = 1)
                                Text(tripDate(t.startDate, t.endDate), color = L.muted, fontSize = 11.sp)
                            }
                            TextButton({ vm.setTripArchived(t.id, false) }) { Text("Restore", fontSize = 12.sp) }
                            TextButton({ deleteId = t.id }) { Text("Delete", color = L.red, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    } else {
        val t = openTrip
        val tripMemberIds = remember(members, t.id) { members.filter { it.tripId == t.id }.map { it.personId } }
        val debtsHere = debts.filter { it.tripId == t.id }.sortedWith(compareBy({ it.settled }, { it.id }))
        val txnsHere = txns.filter { it.tripId == t.id }.sortedByDescending { it.date }
        val spent = spentOn(t)
        val (line, tone) = yourLine(t.id)
        LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp)) {
            item {
                Column(Modifier.padding(8.dp, 0.dp, 8.dp, 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Cap("‹  All trips", Modifier.clickable { openId = 0 }.padding(vertical = 8.dp), color = L.text)
                        Cap("Edit", Modifier.clickable { editorId = t.id; editorError = "" }.padding(8.dp), color = L.text)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t.name, color = L.text, fontSize = 24.sp)
                    val meta = listOfNotNull(t.place.takeIf { it.isNotBlank() }, tripDate(t.startDate, t.endDate)).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = L.muted, fontSize = 13.sp)
                    if (t.note.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(t.note, color = L.muted, fontSize = 13.sp, lineHeight = 18.sp) }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tripMemberIds.forEach { id ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Avatar(tripWho(people, id), people.firstOrNull { it.id == id }?.photoUri ?: "", 38)
                                Spacer(Modifier.height(4.dp))
                                Text(tripWho(people, id), color = L.muted, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (t.budget > 0) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(L.border)) {
                            Box(Modifier.fillMaxWidth((spent / t.budget).coerceIn(0.0, 1.0).toFloat()).height(6.dp).background(if (spent > t.budget) L.red else L.accent))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Spent ${m(inr(spent))}${if (t.budget > 0) " / ${m(inr(t.budget))}" else ""}", color = L.muted, fontSize = 12.sp)
                        Num(m(line), size = 12.sp, color = tone)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoButton("+ Split here", { expenseFor = t.id; expenseError = "" }, Modifier.weight(1f))
                        MonoButton("Add expense", { vm.newDraft("EXPENSE", t.id) }, Modifier.weight(1f), filled = false)
                    }
                }
            }
            item { Cap("Who owes whom", Modifier.padding(24.dp, 8.dp, 24.dp, 4.dp)) }
            if (debtsHere.isEmpty()) item {
                Text("No debts yet. Use “Split here” to add what someone paid for the group.",
                    color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 4.dp))
            } else item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    debtsHere.forEachIndexed { i, d ->
                        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${tripWho(people, d.fromPersonId)}  →  ${tripWho(people, d.toPersonId)}",
                                    color = if (d.settled) L.muted else L.text, fontSize = 14.sp, maxLines = 1)
                                val sub = listOfNotNull(d.note.takeIf { it.isNotBlank() }, if (d.settled) "settled" else "open").joinToString(" · ")
                                Text(sub, color = if (d.settled) L.muted else L.accent, fontSize = 11.sp, maxLines = 1)
                            }
                            Num(m(inr(d.amount)), size = 14.sp, color = if (d.settled) L.muted else L.text)
                            if (!d.settled) TextButton({ settleTarget = d; settleError = "" }) { Text("Settle", fontSize = 12.sp) }
                        }
                        if (i < debtsHere.lastIndex) Hairline(Modifier.padding(start = 16.dp))
                    }
                }
            }
            item { Cap("Expenses on this trip", Modifier.padding(24.dp, 12.dp, 24.dp, 4.dp)) }
            if (txnsHere.isEmpty()) item {
                Text("Nothing linked yet.", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 4.dp))
            } else items(txnsHere, key = { it.id }) { x ->
                Row(Modifier.fillMaxWidth().padding(24.dp, 8.dp)
                    .clickable(onClick = { if (!x.type.startsWith("PERSON")) vm.edit(x) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    HeadTile(x.category, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.note.ifBlank { x.merchant }.ifBlank { x.category }, color = L.text, fontSize = 14.sp, maxLines = 1)
                        val sub = listOfNotNull(dayLabel(x.date), x.personId.takeIf { it != 0L }?.let { tripWho(people, it) }).joinToString(" · ")
                        Text(sub, color = L.muted, fontSize = 11.sp, maxLines = 1)
                    }
                    Num(m(inr(x.amount)), size = 14.sp, color = L.text)
                }
            }
            item {
                Box(Modifier.padding(24.dp, 16.dp)) {
                    Cap("Archive trip", Modifier.clickable { archiveId = t.id }.padding(8.dp), color = L.red)
                }
            }
        }
    }

    if (editorId != null) TripDialog(
        people = people, id = editorId!!, trips = trips, tripMembers = members, error = editorError,
        onDismiss = { editorId = null },
        onSave = { draft ->
            editorError = ""
            vm.saveTrip(draft.id, draft.name, draft.place, draft.start, draft.end, draft.budget, draft.note, draft.members,
                done = { id -> editorId = null; openId = id },
                fail = { editorError = it })
        }
    )

    if (expenseFor != 0L) TripExpenseDialog(
        people = people, memberIds = members.filter { it.tripId == expenseFor }.map { it.personId },
        accounts = accs, error = expenseError,
        onDismiss = { expenseFor = 0L },
        onSave = { e ->
            expenseError = ""
            vm.addTripExpense(expenseFor, e.title, e.amount, e.payer, e.participants, e.weights, e.accountId, e.date,
                done = { expenseFor = 0L }, fail = { expenseError = it })
        }
    )

    settleTarget?.let { d ->
        val needsAccount = d.fromPersonId == 0L || d.toPersonId == 0L
        var acc by remember(d.id) { mutableLongStateOf(accs.firstOrNull { it.isDefault }?.id ?: accs.firstOrNull()?.id ?: 0L) }
        var note by remember(d.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { settleTarget = null }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap("Settle debt", color = L.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${tripWho(people, d.fromPersonId)} pays ${tripWho(people, d.toPersonId)} ${inr(d.amount)}.",
                        color = L.text, fontSize = 14.sp)
                    if (needsAccount) {
                        Picker("Account", accs.firstOrNull { it.id == acc }?.name ?: "Select", accs.map { it.name to it.id }, { acc = it })
                        Text("Money moves through your account, so your own balance changes too.", color = L.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    } else {
                        Text("They settle it between themselves. Only the trip record changes, not your balance.",
                            color = L.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Field("Note (optional)", note, { note = it })
                    if (settleError.isNotBlank()) Text(settleError, color = L.red, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = !needsAccount || acc != 0L, onClick = {
                    vm.settleTripDebt(d, acc, note.trim(),
                        done = { settleTarget = null }, fail = { settleError = it })
                }) { Text("Settle") }
            },
            dismissButton = { TextButton({ settleTarget = null }) { Text("Cancel") } }
        )
    }

    if (archiveId != 0L) {
        AlertDialog(
            onDismissRequest = { archiveId = 0L }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap("Archive this trip?", color = L.text) },
            text = { Text("The trip is hidden from the list but its expenses, people and debts stay. You can restore it from Archived.",
                color = L.muted, fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton({ vm.setTripArchived(archiveId, true); openId = 0L; archiveId = 0L }) { Text("Archive") }
            },
            dismissButton = { TextButton({ archiveId = 0L }) { Text("Cancel") } }
        )
    }

    if (deleteId != 0L) {
        AlertDialog(
            onDismissRequest = { deleteId = 0L }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap("Delete permanently?", color = L.text) },
            text = { Text("The trip, its people and its debts are erased. Expenses in your ledger stay, but lose their trip link. This cannot be undone.",
                color = L.muted, fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                TextButton({ vm.deleteTrip(deleteId); deleteId = 0L }) { Text("Delete", color = L.red) }
            },
            dismissButton = { TextButton({ deleteId = 0L }) { Text("Cancel") } }
        )
    }
}

data class TripDraft(
    val id: Long, val name: String, val place: String, val start: Long, val end: Long,
    val budget: Double, val note: String, val members: List<Long>
)

data class TripExpenseDraft(
    val title: String, val amount: Double, val payer: Long, val participants: List<Long>,
    val weights: Map<Long, Double>, val accountId: Long, val date: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripDialog(
    people: List<Person>, id: Long, trips: List<Trip>, tripMembers: List<TripMember>, error: String,
    onDismiss: () -> Unit, onSave: (TripDraft) -> Unit
) {
    val existing = trips.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var place by remember { mutableStateOf(existing?.place ?: "") }
    var start by remember { mutableLongStateOf(existing?.startDate ?: today()) }
    var end by remember { mutableLongStateOf(existing?.endDate ?: today()) }
    var budget by remember { mutableStateOf(if (existing != null && existing.budget > 0) fmtAmt(existing.budget) else "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var selected by remember {
        mutableStateOf(tripMembers.filter { it.tripId == id && it.personId != 0L }.map { it.personId }.toMutableSet())
    }
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    val budgetValue = budget.toDoubleOrNull() ?: 0.0
    val ready = name.isNotBlank() && end >= start && budgetValue >= 0.0 && budgetValue.isFinite()

    if (showStart || showEnd) {
        val whichStart = showStart
        val st = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(if (whichStart) start else end)
                .atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showStart = false; showEnd = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { picked ->
                        val day = pickerToLocal(picked)
                        if (whichStart) { start = day; if (end < day) end = day } else end = maxOf(day, start)
                    }
                    showStart = false; showEnd = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ showStart = false; showEnd = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
        title = { Cap(if (existing == null) "New trip" else "Edit trip", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (error.isNotBlank()) Text(error, color = L.red, fontSize = 12.sp)
                Field("Trip name", name, { name = it.take(60) })
                Field("Place (optional)", place, { place = it.take(60) })
                Column(Modifier.clickable { showStart = true }) {
                    Cap("Starts")
                    Text(dayLabel(start), color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Hairline()
                }
                Column(Modifier.clickable { showEnd = true }) {
                    Cap("Ends")
                    Text(dayLabel(end), color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Hairline()
                }
                Field("Budget (optional, INR)", budget, { budget = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, kb = KeyboardType.Decimal)
                Cap("Who is coming? You is always included.")
                if (people.isEmpty()) Text("Add people in the Splits tab first.", color = L.muted, fontSize = 13.sp)
                else {
                    val rows = people.chunked(4)
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { p ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(L.surface)
                                        .border(1.dp, if (p.id in selected) L.accent else L.border, RoundedCornerShape(16.dp))
                                        .clickable { selected = if (p.id in selected) (selected - p.id).toMutableSet() else (selected + p.id).toMutableSet() }
                                        .padding(8.dp, 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Avatar(p.name, p.photoUri, 38, selected = p.id in selected)
                                    Spacer(Modifier.height(6.dp))
                                    Text(p.name, color = L.text, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Field("Note (optional)", note, { note = it.take(120) })
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                if (ready) onSave(TripDraft(id, name.trim(), place.trim(), start, end, budgetValue, note.trim(), selected.toList()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripExpenseDialog(
    people: List<Person>, memberIds: List<Long>, accounts: List<Account>, error: String,
    onDismiss: () -> Unit, onSave: (TripExpenseDraft) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var payer by remember { mutableLongStateOf(0L) }
    var shares by remember { mutableStateOf(memberIds.toSet()) }
    var mode by remember { mutableStateOf("EQUAL") }
    var weights by remember { mutableStateOf(mapOf<Long, String>()) }
    var acc by remember { mutableLongStateOf(accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id ?: 0L) }
    var date by remember { mutableLongStateOf(today()) }
    var showDate by remember { mutableStateOf(false) }
    val total = amount.toDoubleOrNull() ?: 0.0
    val participants = (listOf(0L) + memberIds.filter { it != 0L }).filter { it in shares }
    val allWeights = participants.associate { id ->
        val v = if (mode == "EQUAL") 1.0 else (weights[id]?.toDoubleOrNull() ?: 1.0)
        id to if (v.isFinite() && v >= 0.0) v else -1.0
    }
    val ready = total > 0.0 && payer in participants && participants.isNotEmpty() &&
        (payer != 0L || acc != 0L) && allWeights.values.all { it >= 0.0 } && allWeights.values.any { it > 0.0 }

    fun toggle(id: Long) {
        shares = if (id in shares) (shares - id).toSet() else (shares + id).toSet()
        weights = weights + (id to (weights[id] ?: "1"))
    }

    if (showDate) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton({ st.selectedDateMillis?.let { date = pickerToLocal(it) }; showDate = false }) { Text("OK") } },
            dismissButton = { TextButton({ showDate = false }) { Text("Cancel") } }
        ) { DatePicker(st) }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
        title = { Cap("Split on trip", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (error.isNotBlank()) Text(error, color = L.red, fontSize = 12.sp)
                Field("What was it?", title, { title = it.take(80) })
                Field("Amount (INR)", amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, kb = KeyboardType.Decimal)
                val payerOptions = (listOf("You" to 0L) + memberIds.filter { it != 0L }
                    .map { tripWho(people, it) to it })
                Picker(
                    label = "Who paid",
                    value = payerOptions.firstOrNull { it.second == payer }?.first ?: "You",
                    options = payerOptions,
                    onPick = { id ->
                        payer = id
                        if (id !in shares) shares = (shares + id).toSet()
                    }
                )
                Cap("Who shares it?")
                if (memberIds.isEmpty()) Text("Add people to the trip first.", color = L.muted, fontSize = 13.sp)
                else {
                    val ids = listOf(0L) + memberIds.filter { it != 0L }
                    ids.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { id ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(L.surface)
                                        .border(1.dp, if (id in shares) L.accent else L.border, RoundedCornerShape(16.dp))
                                        .clickable { toggle(id) }.padding(8.dp, 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Avatar(tripWho(people, id), people.firstOrNull { it.id == id }?.photoUri ?: "", 38, selected = id in shares)
                                    Spacer(Modifier.height(6.dp))
                                    Text(tripWho(people, id), color = L.text, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Equal", mode == "EQUAL") { mode = "EQUAL" }
                    Chip("Custom", mode == "CUSTOM") { mode = "CUSTOM" }
                }
                if (mode == "CUSTOM") {
                    Text("Enter parts, not percentages. Zero means they owe nothing.", color = L.muted, fontSize = 11.sp)
                    participants.forEach { id ->
                        Field(tripWho(people, id), weights[id] ?: "1",
                            { weights = weights + (id to it.filter { c -> c.isDigit() || c == '.' }.take(6)) },
                            modifier = Modifier.fillMaxWidth(), kb = KeyboardType.Decimal)
                    }
                }
                if (payer == 0L) Picker("Account", accounts.firstOrNull { it.id == acc }?.name ?: "Select", accounts.map { it.name to it.id }, { acc = it })
                Column(Modifier.clickable { showDate = true }) {
                    Cap("Date")
                    Text(dayLabel(date), color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Hairline()
                }
                Text("Everyone’s share becomes a debt to the payer. Only debts involving you appear on person pages.",
                    color = L.muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                if (ready) onSave(TripExpenseDraft(title.trim(), total, payer, participants,
                    allWeights.mapValues { if (mode == "EQUAL") 1.0 else it.value }, acc, date))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
