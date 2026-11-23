package com.minimal.ledger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

val CATEGORIES = listOf(
    "Food & Dining", "Auto & Transport", "Bills & Utilities", "Groceries", "Education",
    "Shopping", "Investments", "Gifts", "Other"
)
val INCOME_CATEGORIES = listOf("Salary", "Refunds", "Interest", "Gifts", "Other")
const val OVERALL = "__overall__"

fun startOfDay(ms: Long): Long {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochMilli(ms).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
}
fun today(): Long = startOfDay(System.currentTimeMillis())
fun monthStart(): Long {
    val z = ZoneId.systemDefault()
    return LocalDate.now().withDayOfMonth(1).atStartOfDay(z).toInstant().toEpochMilli()
}
fun fmtAmt(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

data class Draft(
    val id: Long = 0,
    val type: String = "EXPENSE",
    val amount: String = "",
    val merchant: String = "",
    val note: String = "",
    val date: Long = today(),
    val category: String = "Other",
    val accountId: Long = 0,
    val toAccountId: Long = 0,
    val tag: String = "",
    val exclude: Boolean = false
)

fun computeBalances(t: List<Txn>, a: List<Account>): Map<Long, Double> {
    val m = a.associate { it.id to it.openingBalance }.toMutableMap()
    for (x in t) when (x.type) {
        "INCOME" -> m[x.accountId] = (m[x.accountId] ?: 0.0) + x.amount
        "EXPENSE" -> m[x.accountId] = (m[x.accountId] ?: 0.0) - x.amount
        "TRANSFER" -> {
            m[x.accountId] = (m[x.accountId] ?: 0.0) - x.amount
            x.toAccountId?.let { m[it] = (m[it] ?: 0.0) + x.amount }
        }
    }
    return m
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(AppDatabase.get(app))
    val prefs = AppPrefs.get(app)

    val txns: StateFlow<List<Txn>> = repo.txns.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val accounts: StateFlow<List<Account>> = repo.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val budgets: StateFlow<List<Budget>> = repo.budgets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val balances: StateFlow<Map<Long, Double>> =
        combine(txns, accounts) { t, a -> computeBalances(t, a) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val draft = MutableStateFlow<Draft?>(null)

    fun newDraft(type: String = "EXPENSE") {
        draft.value = Draft(type = type, accountId = accounts.value.firstOrNull()?.id ?: 0)
    }

    fun edit(t: Txn) {
        draft.value = Draft(
            t.id, t.type, fmtAmt(t.amount), t.merchant, t.note, t.date, t.category,
            t.accountId, t.toAccountId ?: 0, t.tag, t.excludeFromBudget
        )
    }

    /** Called when a reminder-notification action opens the app. */
    fun fromReminder(type: String, accountId: Long, amount: Double?, merchant: String?) {
        draft.value = Draft(
            type = type,
            accountId = if (accountId != 0L) accountId else (accounts.value.firstOrNull()?.id ?: 0),
            amount = amount?.let { fmtAmt(it) } ?: "",
            merchant = merchant ?: ""
        )
    }

    fun save(t: Txn) = viewModelScope.launch { repo.save(t); draft.value = null }
    fun delete(t: Txn) = viewModelScope.launch { repo.delete(t) }
    fun restore(t: Txn) = viewModelScope.launch { repo.restore(t) }
    fun saveAccount(a: Account) = viewModelScope.launch { repo.saveAccount(a) }
    fun deleteAccount(id: Long) = viewModelScope.launch { repo.deleteAccount(id) }
    fun setBudget(name: String, amount: Double) = viewModelScope.launch { repo.setBudget(name, amount) }

    fun exportCsv(uri: Uri, done: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching { repo.exportCsv(getApplication<Application>().contentResolver, uri) }
        done(r.fold({ "Exported $it transactions" }, { "Export failed" }))
    }

    fun importCsv(uri: Uri, done: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching { repo.importCsv(getApplication<Application>().contentResolver, uri) }
        done(r.fold({ "Imported $it transactions" }, { "Import failed: not a valid ledger CSV" }))
    }
}
