package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import kotlin.math.abs

private fun balanceText(b: Double) = when {
    b > 0.004 -> "owes you ${inr(b)}"
    b < -0.004 -> "you owe ${inr(b)}"
    else -> "all settled"
}

@Composable
private fun Avatar(name: String, size: Int = 44) {
    val c = headColor(name, L.dark)
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = c, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PeopleView(vm: MainViewModel, modifier: Modifier = Modifier) {
    val people by vm.persons.collectAsState()
    val bal by vm.personBalances.collectAsState()
    val txns by vm.txns.collectAsState()
    val accs by vm.accounts.collectAsState()
    val hide by vm.prefs.hideBalances.collectAsState()
    var openId by rememberSaveable { mutableLongStateOf(0L) }
    var adding by remember { mutableStateOf(false) }
    var editingPerson by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    fun m(s: String) = if (hide) "••••" else s
    val person = people.firstOrNull { it.id == openId }

    if (person == null) {
        val toGet = bal.values.filter { it > 0 }.sum()
        val toPay = -bal.values.filter { it < 0 }.sum()
        LazyColumn(modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(24.dp, 8.dp, 24.dp, 18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column { Cap("You’ll get"); Spacer(Modifier.height(6.dp)); Num(m(inr(toGet)), size = 30.sp, weight = FontWeight.Light, color = L.green) }
                        Column { Cap("You owe"); Spacer(Modifier.height(6.dp)); Num(m(inr(toPay)), size = 30.sp, weight = FontWeight.Light, color = if (toPay > 0) L.red else L.text) }
                    }
                }
            }
            if (people.isEmpty()) item {
                Text("Split a bill, buy something for a friend, or lend money, and it shows up here. Add someone to start.", color = L.muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(24.dp, 4.dp, 24.dp, 16.dp))
            } else item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    people.forEachIndexed { i, p ->
                        val b = bal[p.id] ?: 0.0
                        Row(Modifier.fillMaxWidth().clickable { openId = p.id }.padding(18.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.name)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name + if (p.relation.isNotBlank()) " · ${p.relation}" else "", color = L.text, fontSize = 16.sp)
                                Text(m(balanceText(b)), color = if (b > 0.004) L.green else if (b < -0.004) L.red else L.muted, fontSize = 12.sp)
                            }
                            Text("›", color = L.muted, fontSize = 22.sp)
                        }
                        if (i < people.lastIndex) Hairline(Modifier.padding(start = 76.dp))
                    }
                }
            }
            item { Box(Modifier.padding(24.dp)) { MonoButton("+ Add person", { adding = true }, filled = false) } }
            item { Spacer(Modifier.height(110.dp)) }
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
                        Avatar(person.name, 56); Spacer(Modifier.width(16.dp))
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
                        MonoButton(if (b > 0) "Settle up · they pay ${inr(b)}" else "Settle up · you pay ${inr(b)}", { dialog = if (b > 0) "RECEIVE" else "GIVE" }, Modifier.fillMaxWidth(), filled = false)
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
                        Row(Modifier.fillMaxWidth().padding(18.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Box(Modifier.padding(24.dp)) { Cap("Remove ${person.name}", Modifier.clickable { vm.deletePerson(person.id); openId = 0 }.padding(8.dp), color = L.red) }
            }
            item { Spacer(Modifier.height(110.dp)) }
        }
    }

    if (adding) PersonDialog(null, false, { adding = false }, { vm.savePerson(it); adding = false }, {})
    if (editingPerson && person != null) PersonDialog(
        person, abs(bal[person.id] ?: 0.0) < 0.005, { editingPerson = false }, { vm.savePerson(it); editingPerson = false },
        { vm.deletePerson(person.id); openId = 0; editingPerson = false }
    )
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
                    if (v > 0 && person != null && (kind == "THEY_PAID" || acc != 0L)) { vm.settle(person.id, kind, round2(v), acc, note.trim()); dialog = null }
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PersonDialog(person: Person?, canRemove: Boolean, onDismiss: () -> Unit, onSave: (Person) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(person?.name ?: "") }
    var relation by remember { mutableStateOf(person?.relation ?: "") }
    var phone by remember { mutableStateOf(person?.phone ?: "") }
    var note by remember { mutableStateOf(person?.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(20.dp),
        title = { Cap(if (person == null) "New person" else "Edit person", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Name", name, { name = it })
                Cap("Relation")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Family", "Friend", "Colleague", "Other").forEach { r -> Chip(r, relation == r) { relation = if (relation == r) "" else r } }
                }
                Field("Phone (optional)", phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, kb = KeyboardType.Phone)
                Field("Note (optional)", note, { note = it })
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) onSave(Person(person?.id ?: 0, name.trim(), relation, phone.trim(), note.trim())) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (person != null && canRemove) TextButton(onDelete) { Text("Remove", color = L.red) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Opens a pre-written message: SMS to their number if saved, otherwise the share sheet (WhatsApp etc.). */
fun remind(c: Context, p: Person, amount: Double) {
    val text = "Hi ${p.name}, a gentle reminder about the ${inr(amount)} we need to settle. Thanks!"
    val i = if (p.phone.isNotBlank()) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + p.phone.filter { it.isDigit() || it == '+' })).putExtra("sms_body", text)
    else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Remind via")
    runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
