package com.mtracker.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs

private fun balanceText(b: Double) = when {
    b > 0.004 -> "owes you ${inr(b)}"
    b < -0.004 -> "you owe ${inr(b)}"
    else -> "all settled"
}

private fun shortBalance(b: Double) = when {
    b > 0.004 -> "gets ${inr(b)}"
    b < -0.004 -> "owes ${inr(-b)}"
    else -> "settled"
}

/** Profile photos are copied into app-private storage and referenced by file name only. */
private fun copyPersonPhoto(c: Context, uri: Uri): String? {
    val ext = when (c.contentResolver.getType(uri)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }
    val name = "pfp_" + UUID.randomUUID().toString().replace("-", "") + ext
    val file = File(c.filesDir, name)
    return try {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        require(bytes.size <= 8_000_000) { "Image is too large." }
        file.writeBytes(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..8000 && bounds.outHeight in 1..8000) { "Not a usable image." }
        name
    } catch (e: Exception) {
        file.delete()
        null
    }
}

private fun loadPersonPhoto(c: Context, name: String, target: Int = 128) = runCatching<android.graphics.Bitmap?> {
    if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return@runCatching null
    val file = File(c.filesDir, name)
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / target)
    val raw = file.inputStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return@runCatching null
    Bitmap.createScaledBitmap(raw, target, target, true)
}.getOrNull()

@Composable
fun Avatar(name: String, file: String = "", size: Int = 44, selected: Boolean = false) {
    val ctx = LocalContext.current
    val bmp = remember(file, size) { loadPersonPhoto(ctx, file, size * 2) }
    val shape = CircleShape
    val tint = headColor(name, L.dark)
    Box(
        Modifier.size(size.dp).clip(shape).background(tint.copy(alpha = 0.18f))
            .then(if (selected) Modifier.border(2.dp, L.accent, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
        } else {
            Text(name.trim().take(1).uppercase(), color = tint, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Medium)
        }
    }
}

data class GroupDraft(
    val name: String,
    val note: String,
    val members: List<Long>,
    val total: Double,
    val mode: String,
    val weights: Map<Long, Double>,
    val paidBy: Long,
    val accountId: Long,
    val date: Long
)

@Composable
fun PeopleView(vm: MainViewModel, modifier: Modifier = Modifier) {
    val people by vm.persons.collectAsState()
    val bal by vm.personBalances.collectAsState()
    val txns by vm.txns.collectAsState()
    val allAccs by vm.accounts.collectAsState()
    val accs = remember(allAccs) { allAccs.filter { !it.archived } }
    val groups by vm.groups.collectAsState()
    val groupMembers by vm.groupMembers.collectAsState()
    val groupDebts by vm.groupDebts.collectAsState()
    val tripDebts by vm.tripDebts.collectAsState()
    val hide by vm.prefs.hideBalances.collectAsState()
    var openId by rememberSaveable { mutableLongStateOf(0L) }
    var adding by remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf(false) }
    var rare by remember { mutableStateOf(false) }
    var groupEditor by remember { mutableStateOf(false) }
    var groupError by remember { mutableStateOf("") }
    var openGroupId by remember { mutableLongStateOf(0L) }
    var groupSettleError by remember { mutableStateOf("") }
    var deletePersonId by remember { mutableLongStateOf(0L) }
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    fun m(s: String) = if (hide) "••••" else s
    fun who(id: Long) = if (id == 0L) "You" else people.firstOrNull { it.id == id }?.name ?: "Removed person"
    val person = people.firstOrNull { it.id == openId }

    if (person == null) {
        val toGet = bal.values.filter { it > 0 }.sum()
        val toPay = -bal.values.filter { it < 0 }.sum()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(8.dp, 0.dp, 8.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Cap("You’ll get"); Spacer(Modifier.height(6.dp)); Num(m(inr(toGet)), size = 30.sp, weight = FontWeight.Light, color = L.green) }
                    Column { Cap("You owe"); Spacer(Modifier.height(6.dp)); Num(m(inr(toPay)), size = 30.sp, weight = FontWeight.Light, color = if (toPay > 0) L.red else L.text) }
                }
            }
            if (groups.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Cap("Groups", Modifier.padding(8.dp, 0.dp, 8.dp, 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        groups.forEach { g ->
                            val open = groupDebts.filter { it.groupId == g.id && !it.settled }
                            val toYou = open.filter { it.toPersonId == 0L }.sumOf { it.amount }
                            val owe = open.filter { it.fromPersonId == 0L }.sumOf { it.amount }
                            val tone = if (toYou > 0.004) L.green else if (owe > 0.004) L.red else L.muted
                            val line = when {
                                toYou > 0.004 -> "you get ${inr(toYou)}"
                                owe > 0.004 -> "you owe ${inr(owe)}"
                                else -> "all settled"
                            }
                            val count = groupMembers.count { it.groupId == g.id }
                            Column(
                                Modifier.width(158.dp).clip(RoundedCornerShape(18.dp)).background(L.surface)
                                    .border(1.dp, L.border, RoundedCornerShape(18.dp))
                                    .clickable { openGroupId = g.id; groupSettleError = "" }.padding(14.dp)
                            ) {
                                Text(g.name, color = L.text, fontSize = 14.sp, maxLines = 1)
                                Spacer(Modifier.height(6.dp))
                                Num(m(line), size = 12.sp, color = tone)
                                Spacer(Modifier.height(6.dp))
                                Text("$count people · ${open.size} open", color = L.muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (people.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Split a bill, lend money, or buy something for a friend. Add someone to start.",
                    color = L.muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(8.dp, 4.dp, 8.dp, 12.dp))
            }
            items(people, key = { it.id }) { p ->
                val b = bal[p.id] ?: 0.0
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(L.surface)
                        .border(1.dp, L.border, RoundedCornerShape(20.dp))
                        .clickable { openId = p.id }.padding(12.dp, 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(p.name, p.photoUri, 50)
                    Spacer(Modifier.height(12.dp))
                    Text(p.name, color = L.text, fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    val tone = if (b > 0.004) L.green else if (b < -0.004) L.red else L.muted
                    Num(m(shortBalance(b)), size = 11.sp, color = tone)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(8.dp, 6.dp, 8.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("+ Add person", { adding = true }, Modifier.weight(1f), filled = false)
                    MonoButton("⋯", { rare = true }, Modifier.width(58.dp), filled = false)
                }
            }
        }
    } else {
        val b = bal[person.id] ?: 0.0
        val mine = txns.filter { it.personId == person.id }
        LazyColumn(modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(24.dp, 4.dp, 24.dp, 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Cap("‹  All people", Modifier.clickable { openId = 0 }.padding(vertical = 8.dp), color = L.text)
                        Cap("Edit", Modifier.clickable { editingPerson = true }.padding(8.dp), color = L.text)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person.name, person.photoUri, 56)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(person.name, color = L.text, fontSize = 22.sp)
                            Text(m(balanceText(b)), color = if (b > 0.004) L.green else if (b < -0.004) L.red else L.muted, fontSize = 14.sp)
                            val meta = listOfNotNull(person.relation.takeIf { it.isNotBlank() }, person.phone.takeIf { it.isNotBlank() }).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, color = L.muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoButton("Lend", { dialog = "GIVE" }, Modifier.weight(1f))
                        MonoButton("Repaid", { dialog = "RECEIVE" }, Modifier.weight(1f), filled = false)
                        MonoButton("Borrow", { dialog = "THEY_PAID" }, Modifier.weight(1f), filled = false)
                    }
                    if (abs(b) > 0.004) {
                        Spacer(Modifier.height(10.dp))
                        MonoButton(if (b > 0) "Settle up · they pay ${inr(b)}" else "Settle up · you pay ${inr(b)}",
                            { dialog = if (b > 0) "RECEIVE" else "GIVE" }, Modifier.fillMaxWidth(), filled = false)
                        if (b > 0) { Spacer(Modifier.height(8.dp)); MonoButton("Send a reminder", { remind(ctx, person, b) }, Modifier.fillMaxWidth(), filled = false) }
                    }
                    if (person.note.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(person.note, color = L.muted, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }
            item(key = "hist") { Cap("History", Modifier.padding(24.dp, 8.dp, 24.dp, 8.dp)) }
            if (mine.isEmpty()) item { Text("Nothing yet.", color = L.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp, 4.dp)) }
            else item(key = "histlist") {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    mine.forEachIndexed { i, t ->
                        val title = when (t.type) {
                            "PERSON_OUT" -> "Lent"
                            "PERSON_IN" -> "Received"
                            "PERSON_ADJ" -> "Borrowed"
                            else -> t.note.ifBlank { t.merchant }.ifBlank { t.category }
                        }
                        val sub = dayLabel(t.date) + when {
                            t.type == "EXPENSE" && t.owed >= t.amount - 0.005 && t.owed > 0 -> " · you covered ${inr(t.amount)}"
                            t.type == "EXPENSE" && t.owed > 0 -> " · bill ${inr(t.amount)} split"
                            t.type == "EXPENSE" -> " · noted only (${inr(t.amount)})"
                            t.note.isNotBlank() -> " · ${t.note}"
                            else -> ""
                        }
                        Row(Modifier.fillMaxWidth().padding(18.dp, 12.dp)
                            .clickable(onClick = { if (!t.type.startsWith("PERSON")) vm.edit(t) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (t.type == "EXPENSE") HeadTile(t.category, 40.dp) else HeadTile("People", 40.dp, stored = "person")
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, color = L.text, fontSize = 15.sp, maxLines = 1)
                                Text(sub, color = L.muted, fontSize = 12.sp, maxLines = 2)
                            }
                            Spacer(Modifier.width(10.dp))
                            if (t.owed != 0.0) Num(m((if (t.owed > 0) "+" else "−") + inr(t.owed)), size = 15.sp, color = if (t.owed > 0) L.green else L.red)
                            else Num("—", size = 15.sp, color = L.muted)
                        }
                        if (i < mine.lastIndex) Hairline(Modifier.padding(start = 72.dp))
                    }
                }
            }
            if (abs(b) < 0.005) item {
                Box(Modifier.padding(24.dp)) { Cap("Remove ${person.name}", Modifier.clickable { deletePersonId = person.id }.padding(8.dp), color = L.red) }
            }
            item { Spacer(Modifier.height(110.dp)) }
        }
    }

    if (adding) PersonDialog(null, false, { adding = false }, { vm.savePerson(it); adding = false }, {})
    if (editingPerson && person != null) PersonDialog(
        person, abs(bal[person.id] ?: 0.0) < 0.005, { editingPerson = false },
        { vm.savePerson(it); editingPerson = false },
        { deletePersonId = person.id; openId = 0; editingPerson = false }
    )

    if (deletePersonId != 0L) {
        val target = people.firstOrNull { it.id == deletePersonId }
        val tripCount = tripDebts.count { it.fromPersonId == deletePersonId || it.toPersonId == deletePersonId }
        val groupCount = groupDebts.count { it.fromPersonId == deletePersonId || it.toPersonId == deletePersonId }
        val debtCount = tripCount + groupCount
        AlertDialog(
            onDismissRequest = { deletePersonId = 0L }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap("Remove ${target?.name ?: "person"}?", color = L.text) },
            text = {
                Text("Their entries stay in your ledger but lose the person label. " +
                    (if (debtCount > 0) "This also erases $debtCount trip/group debt(s), including ones between other people. This cannot be undone."
                    else "This cannot be undone."),
                    color = L.muted, fontSize = 13.sp, lineHeight = 18.sp)
            },
            confirmButton = {
                TextButton({
                    vm.deletePerson(deletePersonId)
                    deletePersonId = 0L
                    openId = 0L
                }) { Text("Remove", color = L.red) }
            },
            dismissButton = { TextButton({ deletePersonId = 0L }) { Text("Cancel") } }
        )
    }

    if (rare) AlertDialog(
        onDismissRequest = { rare = false }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
        title = { Cap("Rare actions", color = L.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Occasion groups stay out of the daily flow. Removing a group keeps its ledger entries.",
                    color = L.muted, fontSize = 12.sp, lineHeight = 17.sp)
                groups.forEach { g ->
                    val names = groupMembers.filter { it.groupId == g.id }
                        .mapNotNull { m -> people.firstOrNull { it.id == m.personId }?.name }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = L.text, fontSize = 14.sp, maxLines = 1)
                            Text(names.joinToString(", ").ifBlank { "No people" }, color = L.muted, fontSize = 11.sp, maxLines = 1)
                        }
                        TextButton({ vm.deleteGroup(g.id) }) { Text("Remove", color = L.red, fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ rare = false; groupError = ""; groupEditor = true }) { Text("New group") } },
        dismissButton = { TextButton({ rare = false }) { Text("Close") } }
    )

    if (groupEditor) GroupDialog(
        people = people, accounts = accs, error = groupError,
        onDismiss = { groupEditor = false },
        onSave = { d ->
            groupError = ""
            vm.saveGroup(d.name, d.note, d.members, d.total, d.mode, d.weights, d.paidBy, d.accountId, d.date,
                done = { groupEditor = false },
                fail = { groupError = it })
        }
    )

    val openGroup = groups.firstOrNull { it.id == openGroupId }
    if (openGroup != null) {
        val debts = groupDebts.filter { it.groupId == openGroup.id }.sortedBy { it.settled }
        val settleAccId = accs.firstOrNull { it.isDefault }?.id ?: accs.firstOrNull()?.id ?: 0L
        AlertDialog(
            onDismissRequest = { openGroupId = 0 }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap(openGroup.name, color = L.text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (openGroup.note.isNotBlank()) Text(openGroup.note, color = L.muted, fontSize = 12.sp)
                    Text("Everyone’s share is shown here. Only debts between you and someone else appear on their person page.",
                        color = L.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    if (groupSettleError.isNotBlank()) Text(groupSettleError, color = L.red, fontSize = 12.sp)
                    if (debts.isEmpty()) Text("No debts recorded.", color = L.muted, fontSize = 13.sp)
                    debts.forEach { d ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${who(d.fromPersonId)}  →  ${who(d.toPersonId)}", color = L.text, fontSize = 13.sp, maxLines = 1)
                                Text(if (d.settled) "settled" else "open", color = if (d.settled) L.muted else L.accent, fontSize = 11.sp)
                            }
                            Num(m(inr(d.amount)), size = 13.sp, color = if (d.settled) L.muted else L.text)
                            if (!d.settled && (d.fromPersonId == 0L || d.toPersonId == 0L)) {
                                TextButton(onClick = {
                                    vm.settleGroupDebt(d, settleAccId, done = {}, fail = { groupSettleError = it })
                                }, enabled = settleAccId != 0L) { Text("Settle", fontSize = 12.sp) }
                            }
                        }
                        Hairline()
                    }
                }
            },
            confirmButton = {
                TextButton({ vm.deleteGroup(openGroup.id); openGroupId = 0 }) { Text("Remove group", color = L.red) }
            },
            dismissButton = { TextButton({ openGroupId = 0 }) { Text("Close") } }
        )
    }

    dialog?.let { kind ->
        val b = person?.let { bal[it.id] } ?: 0.0
        val name = person?.name ?: ""
        var amt by remember(kind) { mutableStateOf(if (abs(b) > 0.004 && ((kind == "RECEIVE" && b > 0) || (kind == "GIVE" && b < 0))) fmtAmt(abs(b)) else "") }
        var acc by remember(kind) { mutableLongStateOf(if (kind == "THEY_PAID") 0L else accs.firstOrNull()?.id ?: 0L) }
        val accList = (if (kind == "THEY_PAID") listOf("No account (they paid directly)" to 0L) else emptyList()) + accs.map { it.name to it.id }
        var note by remember(kind) { mutableStateOf("") }
        val title = when (kind) {
            "GIVE" -> "Lend to $name"
            "RECEIVE" -> "$name repaid you"
            else -> "Borrow from $name"
        }
        AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
            title = { Cap(title, color = L.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Field("Amount (INR)", amt, { amt = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, kb = KeyboardType.Decimal)
                    Picker("Account", accList.firstOrNull { it.second == acc }?.first ?: "Select", accList, { acc = it })
                    Field("Note (optional)", note, { note = it })
                    Text(
                        when (kind) {
                            "GIVE" -> "Money leaves the account. $name owes you more."
                            "RECEIVE" -> "Money comes in. $name owes you less."
                            else -> "You owe $name more. Pick an account only if they handed you cash."
                        }, color = L.muted, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton({
                    val v = amt.toDoubleOrNull() ?: 0.0
                    if (v > 0 && person != null && (kind == "THEY_PAID" || acc != 0L)) {
                        vm.settle(person.id, kind, round2(v), acc, note.trim()); dialog = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PersonDialog(person: Person?, canRemove: Boolean, onDismiss: () -> Unit, onSave: (Person) -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(person?.name ?: "") }
    var relation by remember { mutableStateOf(person?.relation ?: "") }
    var phone by remember { mutableStateOf(person?.phone ?: "") }
    var note by remember { mutableStateOf(person?.note ?: "") }
    var photo by remember { mutableStateOf(person?.photoUri ?: "") }
    var photoError by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val saved = copyPersonPhoto(ctx, uri)
            if (saved != null) { photo = saved; photoError = "" } else photoError = "Couldn’t use that image."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
        title = { Cap(if (person == null) "New person" else "Edit person", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.ifBlank { "?" }, photo, 52)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        MonoButton(if (photo.isBlank()) "Add photo" else "Change photo", { pick.launch("image/*") }, filled = false)
                        if (photo.isNotBlank()) TextButton({ photo = "" }) { Text("Clear", color = L.muted, fontSize = 12.sp) }
                    }
                }
                if (photoError.isNotBlank()) Text(photoError, color = L.red, fontSize = 12.sp)
                Field("Name", name, { name = it.take(100) })
                Cap("Relation")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Family", "Friend", "Colleague", "Other").forEach { r -> Chip(r, relation == r) { relation = if (relation == r) "" else r } }
                }
                Field("Phone (optional)", phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, kb = KeyboardType.Phone)
                Field("Note (optional)", note, { note = it })
            }
        },
        confirmButton = {
            TextButton({ if (name.isNotBlank()) onSave(Person(person?.id ?: 0, name.trim(), relation, phone.trim(), note.trim(), photo)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (person != null && canRemove) TextButton(onDelete) { Text("Remove", color = L.red) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDialog(
    people: List<Person>,
    accounts: List<Account>,
    error: String,
    onDismiss: () -> Unit,
    onSave: (GroupDraft) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("EQUAL") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var weights by remember { mutableStateOf(mapOf<Long, String>()) }
    var paidBy by remember { mutableLongStateOf(0L) }
    var acc by remember { mutableLongStateOf(accounts.firstOrNull { it.isDefault }?.id ?: accounts.firstOrNull()?.id ?: 0L) }
    var date by remember { mutableStateOf(today()) }
    var showDate by remember { mutableStateOf(false) }

    val chosen = people.filter { it.id in selected }
    val participants = listOf(0L) + chosen.map { it.id }
    val payerOptions = listOf("You" to 0L) + chosen.map { it.name to it.id }
    val weightValues = if (mode == "EQUAL") {
        participants.associateWith { 1.0 }
    } else {
        participants.associate { id ->
            val v = weights[id]?.toDoubleOrNull() ?: 1.0
            id to if (v.isFinite() && v >= 0.0) v else -1.0
        }
    }
    val total = amount.toDoubleOrNull() ?: 0.0
    val ready = name.isNotBlank() && total > 0.0 && chosen.isNotEmpty() && (paidBy != 0L || acc != 0L) &&
        (paidBy == 0L || paidBy in selected) && weightValues.values.all { it >= 0.0 } && weightValues.values.any { it > 0.0 }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
        weights = weights + (id to (weights[id] ?: "1"))
        if (id == paidBy && id !in selected) paidBy = 0L
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
        title = { Cap("Occasion split", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (error.isNotBlank()) Text(error, color = L.red, fontSize = 12.sp)
                Field("Occasion", name, { name = it.take(60) })
                Field("Total amount", amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, kb = KeyboardType.Decimal)
                Cap("Who was there")
                if (people.isEmpty()) Text("Add people first.", color = L.muted, fontSize = 13.sp)
                else {
                    val rows = people.chunked(4)
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { p ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(L.surface)
                                        .border(1.dp, if (p.id in selected) L.accent else L.border, RoundedCornerShape(16.dp))
                                        .clickable { toggle(p.id) }.padding(8.dp, 10.dp),
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
                Cap("Split")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Equal", mode == "EQUAL") { mode = "EQUAL" }
                    Chip("Custom", mode == "CUSTOM") { mode = "CUSTOM" }
                }
                if (mode == "CUSTOM") {
                    Text("Enter parts, not percentages. You is always included.", color = L.muted, fontSize = 11.sp)
                    participants.forEach { id ->
                        val label = if (id == 0L) "You" else people.firstOrNull { it.id == id }?.name ?: "Person"
                        Field(label, weights[id] ?: "1", { weights = weights + (id to it.filter { c -> c.isDigit() || c == '.' }.take(6)) },
                            modifier = Modifier.fillMaxWidth(), kb = KeyboardType.Decimal)
                    }
                }
                Picker("Paid by", payerOptions.firstOrNull { it.second == paidBy }?.first ?: "You", payerOptions, { paidBy = it })
                Picker("Account", accounts.firstOrNull { it.id == acc }?.name ?: "Select", accounts.map { it.name to it.id }, { acc = it })
                Column(Modifier.clickable { showDate = true }) {
                    Cap("Date")
                    Text(dayLabel(date), color = L.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Hairline()
                }
                Field("Note (optional)", note, { note = it.take(120) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ready) onSave(GroupDraft(name.trim(), note.trim(), selected.toList(), total, mode, weightValues, paidBy, acc, date))
            }, enabled = ready) { Text("Split") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/** Opens a pre-written message: SMS to their number if saved, otherwise the share sheet (WhatsApp etc.). */
fun remind(c: Context, p: Person, amount: Double) {
    val text = "Hi ${p.name}, a gentle reminder about the ${inr(amount)} we need to settle. Thanks!"
    val i = if (p.phone.isNotBlank()) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + p.phone.filter { it.isDigit() || it == '+' })).putExtra("sms_body", text)
    else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Remind via")
    runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
