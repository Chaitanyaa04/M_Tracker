package com.minimal.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private fun typeName(t: String) = when (t) { "CASH" -> "Cash"; "CARD" -> "Credit card"; else -> "Bank account" }

@Composable
fun AccountsScreen(vm: MainViewModel, onTransfer: () -> Unit, modifier: Modifier = Modifier) {
    val accs by vm.accounts.collectAsState()
    val bal by vm.balances.collectAsState()
    var editing by remember { mutableStateOf<Account?>(null) }
    var adding by remember { mutableStateOf(false) }
    val net = accs.sumOf { bal[it.id] ?: 0.0 }
    val assets = accs.filter { it.type != "CARD" }.sumOf { bal[it.id] ?: 0.0 }
    val owed = -accs.filter { it.type == "CARD" }.sumOf { bal[it.id] ?: 0.0 }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(24.dp, 16.dp, 24.dp, 24.dp)) {
                Cap("Net balance")
                Spacer(Modifier.height(8.dp))
                Num(inrS(net), size = 44.sp, weight = FontWeight.Light, color = if (net < 0) L.red else L.text)
                Spacer(Modifier.height(4.dp))
                Num("Assets ${inrS(assets)}  ·  Cards owed ${inr(owed)}", size = 13.sp, color = L.muted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton("Transfer", onTransfer)
                    MonoButton("+ Account", { adding = true }, filled = false)
                }
            }
            Hairline()
        }
        listOf("BANK" to "Bank accounts", "CASH" to "Cash", "CARD" to "Credit cards").forEach { (type, title) ->
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
                            Num(inrS(b), color = if (b < 0) L.red else L.text)
                        }
                        Hairline()
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
    var open by remember {
        mutableStateOf(account?.let { fmtAmt(if (it.type == "CARD") -it.openingBalance else it.openingBalance) } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(6.dp),
        title = { Cap(if (account == null) "New account" else "Edit account", color = L.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Name", name, { name = it })
                Picker("Type", typeName(type), listOf("Bank account" to "BANK", "Cash" to "CASH", "Credit card" to "CARD"), { type = it })
                Field(if (type == "CARD") "Amount owed (INR)" else "Opening balance (INR)", open, { open = it }, kb = KeyboardType.Decimal)
                if (account != null) Text("Deleting an account also removes its transactions.", color = L.muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton({
                val v = open.toDoubleOrNull() ?: 0.0
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
