package com.mtracker.app

import android.app.Application
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

const val OVERALL = "__overall__"

fun startOfDay(ms: Long): Long {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochMilli(ms).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
}
fun today(): Long = startOfDay(System.currentTimeMillis())
fun dayMillis(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun monthStart(): Long = dayMillis(LocalDate.now().withDayOfMonth(1))
/** Material date pickers return UTC midnight; convert to local start-of-day. */
fun pickerToLocal(utcMs: Long): Long = dayMillis(Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate())
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
    val exclude: Boolean = false,
    val pendingId: Long = 0,
    val repeat: String = "NEVER"
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

/** Returns (consecutive logged days, whether today is already covered). Days with a "no spending" mark count. */
fun computeStreak(t: List<Txn>, m: List<DayMark>): Pair<Int, Boolean> {
    val days = HashSet<Long>()
    t.forEach { days.add(it.date) }
    m.forEach { days.add(it.date) }
    val loggedToday = dayMillis(LocalDate.now()) in days
    var d = if (loggedToday) LocalDate.now() else LocalDate.now().minusDays(1)
    var n = 0
    while (dayMillis(d) in days) { n++; d = d.minusDays(1) }
    return n to loggedToday
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(AppDatabase.get(app))
    val prefs = AppPrefs.get(app)

    private fun <T> kotlinx.coroutines.flow.Flow<T>.eager(init: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.Eagerly, init)

    val txns = repo.txns.eager(emptyList())
    val accounts = repo.accounts.eager(emptyList())
    val budgets = repo.budgets.eager(emptyList())
    val pending = repo.pending.eager(emptyList())
    val recurring = repo.recurring.eager(emptyList())
    val categories = repo.categories.eager(emptyList())
    val dayMarks = repo.dayMarks.eager(emptyList())

    val expenseCats = categories.map { l -> l.filter { it.kind == "EXPENSE" }.map { it.name } }.eager(DEFAULT_EXPENSE_CATEGORIES)
    val incomeCats = categories.map { l -> l.filter { it.kind == "INCOME" }.map { it.name } }.eager(DEFAULT_INCOME_CATEGORIES)
    val balances = combine(txns, accounts) { t, a -> computeBalances(t, a) }.eager(emptyMap())
    val streak = combine(txns, dayMarks) { t, m -> computeStreak(t, m) }.eager(0 to false)

    /** One-tap repeats: newest distinct merchant+amount combos. */
    val recents = txns.map { l ->
        l.filter { it.type != "TRANSFER" && it.merchant.isNotBlank() }
            .distinctBy { it.type + it.merchant.lowercase() + it.amount }.take(12)
    }.eager(emptyList())

    /** Merchant names, most used first (powers autocomplete). */
    val merchantNames = txns.map { l ->
        l.filter { it.type != "TRANSFER" && it.merchant.isNotBlank() }
            .groupingBy { it.merchant.trim() }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }.eager(emptyList())

    val draft = MutableStateFlow<Draft?>(null)
    val locked = MutableStateFlow(false)
    var stoppedAt = 0L

    init { materialize() }

    fun materialize() { viewModelScope.launch { repo.materializeDue() } }

    /** Same merchant as before? Reuse its category/account/tag. */
    fun lookup(m: String): Txn? =
        txns.value.firstOrNull { it.type != "TRANSFER" && it.merchant.trim().equals(m.trim(), ignoreCase = true) }

    private fun firstAcc() = accounts.value.firstOrNull()?.id ?: 0

    fun newDraft(type: String = "EXPENSE") { draft.value = Draft(type = type, accountId = firstAcc()) }

    fun edit(t: Txn) {
        draft.value = Draft(
            id = t.id, type = t.type, amount = fmtAmt(t.amount), merchant = t.merchant, note = t.note, date = t.date,
            category = t.category, accountId = t.accountId, toAccountId = t.toAccountId ?: 0, tag = t.tag, exclude = t.excludeFromBudget
        )
    }

    fun fromReminder(type: String, accountId: Long, amount: Double?, merchant: String?, pendingId: Long) {
        draft.value = Draft(
            type = type, accountId = if (accountId != 0L) accountId else firstAcc(),
            amount = amount?.let { fmtAmt(it) } ?: "", merchant = merchant ?: "", pendingId = pendingId
        )
    }

    fun fromPending(p: Pending, type: String) = fromReminder(type, p.accountId, p.amount, p.merchant, p.id)

    fun dismissPending(p: Pending) = viewModelScope.launch {
        repo.deletePending(p.id)
        NotificationManagerCompat.from(getApplication<Application>()).cancel(p.id.toInt())
    }

    fun save(t: Txn, repeat: String, pendingId: Long) = viewModelScope.launch {
        repo.saveWithRepeat(t, repeat, pendingId)
        if (pendingId != 0L) NotificationManagerCompat.from(getApplication<Application>()).cancel(pendingId.toInt())
        draft.value = null
    }

    fun delete(t: Txn) = viewModelScope.launch { repo.delete(t) }
    fun restore(t: Txn) = viewModelScope.launch { repo.restore(t) }
    fun saveAccount(a: Account) = viewModelScope.launch { repo.saveAccount(a) }
    fun deleteAccount(id: Long) = viewModelScope.launch { repo.deleteAccount(id) }
    fun setBudget(name: String, amount: Double) = viewModelScope.launch { repo.setBudget(name, amount) }
    fun addCategory(name: String, kind: String) = viewModelScope.launch { if (name.isNotBlank()) repo.addCategory(name, kind) }
    fun deleteCategory(name: String, kind: String) = viewModelScope.launch { repo.deleteCategory(name, kind) }
    fun deleteRecurring(id: Long) = viewModelScope.launch { repo.deleteRecurring(id) }
    fun markNoSpend() = viewModelScope.launch { repo.markNoSpend(today()) }

    fun exportCsv(uri: Uri, done: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching { repo.exportCsv(getApplication<Application>().contentResolver, uri) }
        done(r.fold({ "Exported $it transactions" }, { "Export failed" }))
    }

    fun importCsv(uri: Uri, done: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching { repo.importCsv(getApplication<Application>().contentResolver, uri) }
        done(r.fold({ "Imported $it transactions" }, { "Import failed: not a valid ledger CSV" }))
    }

    fun restoreBackup(uri: Uri, done: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching {
            val app = getApplication<Application>()
            val txt = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            BackupManager.restore(AppDatabase.get(app), txt)
        }
        done(r.fold({ "Restored $it transactions" }, { "Restore failed: not an M_Tracker backup" }))
    }

    fun backupNow(done: (String) -> Unit) = viewModelScope.launch {
        val ok = BackupManager.backupToFolder(getApplication<Application>())
        done(if (ok) "Backup saved to your folder" else "Choose a backup folder first")
    }
}
