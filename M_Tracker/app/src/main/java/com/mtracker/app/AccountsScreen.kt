package com.mtracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private fun typeName(t: String) = when (t) {
    "CASH" -> "Cash"
    "CARD" -> "Credit card"
    "ASSET" -> "Portfolio asset"
    else -> "Bank account"
}

private val GROUPS = listOf("BANK" to "Bank accounts", "CASH" to "Cash", "CARD" to "Credit cards", "ASSET" to "Portfolio")

@Composable
fun AccountsScreen(vm: MainViewModel, onTransfer: () -> Unit, modifier: Modifier = Modifier) {
    val accs by vm.accounts.collectAsState()
    val bal by vm.balances.collectAsState()
    val hide by vm.prefs.hideBalances.collectAsState()
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    var view by rememberSaveable { mutableIntStateOf(0) }
    fun m(s: String) = if (hide) "••••••" else s
    fun total(type: String) = accs.filter { it.type == type }.sumOf { bal[it.id] ?: 0.0 }
    val net = accs.sumOf { bal[it.id] ?: 0.0 }
    val owed = -total("CARD")

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 16.dp, 24.dp, 20.dp)) {
                Cap("Net balance")
                Spacer(Modifier.height(8.dp))
                Num(m(inrS(net)), size = 44.sp, weight = FontWeight.Light, color = if (net < 0 && !hide) L.red else L.text)
                Spacer(Modifier.height(4.dp))
                Num(m("Owed on cards ${inr(owed)}"), size = 13.sp, color = L.muted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("Transfer", onTransfer)
                    MonoButton("+ Account", { adding = true }, filled = false)
                }
            }
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(24.dp, 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Accounts", view == 0) { view = 0 }
                    Chip("Summary", view == 1) { view = 1 }
                }
                Cap(if (hide) "Show" else "Hide", Modifier.clickable { vm.prefs.setHide(!hide) }.padding(8.dp), color = L.text)
            }
            Hairline()
        }
        if (view == 0) {
            GROUPS.forEach { (type, title) ->
                val list = accs.filter { it.type == type }
                if (list.isNotEmpty()) {
                    item(key = "t$type") { Cap(title, Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp)) }
                    items(list, key = { it.id }) { a ->
                        val b = bal[a.id] ?: 0.0
                        Column(Modifier.clickable { editing = a }) {
                            Row(
                                Modifier.fillMaxWidth().padding(24.dp, 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(a.name, color = L.text, fontSize = 15.sp)
                                Num(m(inrS(b)), color = if (b < 0 && !hide) L.red else L.text)
                            }
                            Hairline()
                        }
                    }
                }
            }
        } else {
            item(key = "summary") {
                Column {
                    GROUPS.forEach { (type, title) ->
                        val t = total(type)
                        Row(Modifier.fillMaxWidth().padding(24.dp, 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(title, color = L.text, fontSize = 15.sp)
                            Num(m(inrS(t)), color = if (t < 0 && !hide) L.red else L.text)
                        }
                        Hairline()
                    }
                    Row(Modifier.fillMaxWidth().padding(24.dp, 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Cap("Net", color = L.text)
                        Num(m(inrS(net)), weight = FontWeight.Medium, color = if (net < 0 && !hide) L.red else L.text)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    if (adding) AccountDialog(null, { adding = false }, { vm.saveAccount(it); adding = false }, {})
    editing?.let { a ->
        AccountDialog(a, { editing = null }, { vm.saveAccount(it); editing = null }, { vm.deleteAccount(a.id); editing = null })
    }
}

@Composable
fun AccountDialog(account: Account?, onDismiss: () -> Unit, onSave: (Account) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var type by remember { mutableStateOf(account?.type ?: "BANK") }
    var openingText by remember {
        mutableStateOf(account?.let { fmtAmt(if (it.type == "CARD") -it.openingBalance else it.openingBalance) } ?: "")
    }
    val openLabel = when (type) {
        "CARD" -> "Amount owed (INR)"
        "ASSET" -> "Current value (INR)"
        else -> "Opening balance (INR)"
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(6.dp),
        title = { Cap(if (account == null) "New account" else "Edit account", color = L.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Name", name, { name = it })
                Picker(
                    "Type", typeName(type),
                    listOf("Bank account" to "BANK", "Cash" to "CASH", "Credit card" to "CARD", "Portfolio asset" to "ASSET"),
                    { type = it }
                )
                Field(openLabel, openingText, { openingText = it }, kb = KeyboardType.Decimal)
                if (account != null) Text("Deleting an account also removes its transactions.", color = L.muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton({
                val v = openingText.toDoubleOrNull() ?: 0.0
                if (name.isNotBlank()) onSave(Account(account?.id ?: 0, name.trim(), type, if (type == "CARD") -abs(v) else v))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (account != null) TextButton(onDelete) { Text("Delete", color = L.red) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        }
    )
}
