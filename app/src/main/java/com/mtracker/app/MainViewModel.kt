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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

const val OVERALL = "__overall__"
/** Daily caps live in the same table, with this prefix. */
const val DAY_PREFIX = "D|"
const val DAY_OVERALL = "D|__overall__"

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
    val category: String = "",
    val accountId: Long = 0,
    val toAccountId: Long = 0,
    val tag: String = "",
    val exclude: Boolean = false,
    val pendingId: Long = 0,
    val repeat: String = "NEVER",
    val routeFrom: String = "",
    val routeTo: String = "",
    val items: String = "",
    val personId: Long = 0,
    val split: String = "TAG",
    val share: String = "",
    val tripId: Long = 0,
    val createdAt: Long = 0
)

fun computeBalances(t: List<Txn>, a: List<Account>): Map<Long, Double> = LedgerMath.balances(t, a)

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
    val persons = repo.persons.eager(emptyList())
    val deletedTxns = repo.deletedTxns.eager(emptyList())
    val changeHistory = repo.changeHistory.eager(emptyList())
    val groups = repo.groups.eager(emptyList())
    val groupMembers = repo.groupMembers.eager(emptyList())
    val groupDebts = repo.groupDebts.eager(emptyList())
    val trips = repo.trips.eager(emptyList())
    val tripMembers = repo.tripMembers.eager(emptyList())
    val tripDebts = repo.tripDebts.eager(emptyList())
    val peopleNames = persons.map { l -> l.associate { it.id to it.name } }.eager(emptyMap())
    /** personId -> balance. Positive = they owe you, negative = you owe them. */
    val personBalances = txns.map { l -> l.filter { it.personId != 0L }.groupBy { it.personId }.mapValues { e -> e.value.sumOf { it.owed } } }.eager(emptyMap())

    val balances = combine(txns, accounts) { t, a -> computeBalances(t, a) }.eager(emptyMap())
    val streak = combine(txns, dayMarks) { t, m -> computeStreak(t, m) }.eager(0 to false)

    /** One-tap repeats: newest distinct merchant+amount combos. */
    val recents = txns.map { l ->
        l.filter { (it.type == "EXPENSE" || it.type == "INCOME") && (it.merchant.isNotBlank() || it.note.isNotBlank()) }
            .distinctBy { it.type + it.note.lowercase() + it.merchant.lowercase() + it.amount }.take(12)
    }.eager(emptyList())

    /** Merchant names, most used first (powers autocomplete). */
    val merchantNames = txns.map { l ->
        l.filter { (it.type == "EXPENSE" || it.type == "INCOME") && it.merchant.isNotBlank() }
            .groupingBy { it.merchant.trim() }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }.eager(emptyList())

    val usage = txns.map { l ->
        l.filter { it.type != "TRANSFER" }.groupingBy { (if (it.type == "INCOME") "INCOME" else "EXPENSE") + "|" + it.category }.eachCount()
    }.eager(emptyMap())
    val iconMap = categories.map { l -> l.filter { it.icon.isNotEmpty() }.associate { it.name to it.icon } }.eager(emptyMap())

    /** Heads you tend to use around this hour of day (and weekday). Powers the "Likely right now" row. */
    fun likely(kind: String): List<String> {
        val now = java.time.LocalDateTime.now()
        val want = if (kind == "INCOME") "INCOME" else "EXPENSE"
        val z = ZoneId.systemDefault()
        val scores = HashMap<String, Double>()
        for (t in txns.value) {
            if (t.type != want || t.createdAt <= 0) continue
            val at = Instant.ofEpochMilli(t.createdAt).atZone(z)
            val diff = abs(at.hour - now.hour)
            val dh = minOf(diff, 24 - diff)
            if (dh > 2) continue
            val w = if (at.dayOfWeek == now.dayOfWeek) 1.5 else 1.0
            scores[t.category] = (scores[t.category] ?: 0.0) + w
        }
        return scores.entries.sortedByDescending { it.value }.take(5).map { it.key }
    }

    val draft = MutableStateFlow<Draft?>(null)
    val locked = MutableStateFlow(false)
    val settingsTarget = MutableStateFlow<String?>(null)

    init {
        materialize()
        viewModelScope.launch {
            if (!prefs.defaultHeadsSeededV6.value) {
                repo.seedDefaultHeads()
                prefs.setDefaultHeadsSeededV6(true)
            }
        }
    }

    fun materialize() { viewModelScope.launch { repo.materializeDue() } }

    /** Same merchant as before? Reuse its category/account/tag. */
    fun lookup(m: String): Txn? =
        txns.value.firstOrNull { (it.type == "EXPENSE" || it.type == "INCOME") && it.merchant.trim().equals(m.trim(), ignoreCase = true) }

    private fun firstAcc() = accounts.value.firstOrNull { it.isDefault && !it.archived }?.id
        ?: accounts.value.firstOrNull { !it.archived }?.id ?: 0L

    fun newDraft(type: String = "EXPENSE", tripId: Long = 0) { draft.value = Draft(type = type, accountId = firstAcc(), tripId = tripId) }

    fun edit(t: Txn) {
        draft.value = Draft(
            id = t.id, type = t.type, amount = fmtAmt(t.amount), merchant = t.merchant, note = t.note, date = t.date,
            category = t.category, accountId = t.accountId, toAccountId = t.toAccountId ?: 0, tag = t.tag, exclude = t.excludeFromBudget,
            routeFrom = t.routeFrom, routeTo = t.routeTo, items = t.items, personId = t.personId,
            split = if (t.personId == 0L || t.owed == 0.0) "TAG" else if (t.owed >= t.amount - 0.005) "FULL" else "SPLIT",
            share = if (t.owed > 0) fmtAmt(t.owed) else "",
            tripId = t.tripId, createdAt = t.createdAt
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
        prefs.bumpWrong(p.pkg)   // dismissing = a wrong call: be a bit less eager about this app
        NotificationManagerCompat.from(getApplication<Application>()).cancel(p.id.toInt())
    }

    fun save(t: Txn, repeat: String, pendingId: Long) = viewModelScope.launch {
        repo.saveWithRepeat(t, repeat, pendingId)
        if (pendingId != 0L) NotificationManagerCompat.from(getApplication<Application>()).cancel(pendingId.toInt())
        draft.value = null
    }

    fun delete(t: Txn) = viewModelScope.launch { repo.delete(t) }
    fun restore(t: Txn) = viewModelScope.launch { repo.restore(t) }
    fun restoreDeleted(item: DeletedTxn) = viewModelScope.launch { repo.restoreDeleted(item) }
    fun deleteDeletedForever(id: Long) = viewModelScope.launch { repo.deleteDeletedForever(id) }
    fun restoreDefaultHeads() = viewModelScope.launch {
        repo.seedDefaultHeads(); prefs.setDefaultHeadsSeededV6(true)
    }
    fun saveAccount(a: Account) = viewModelScope.launch { repo.saveAccount(a) }
    fun deleteAccount(id: Long) = viewModelScope.launch { repo.deleteAccount(id) }
    fun setBudget(name: String, amount: Double) = viewModelScope.launch { repo.setBudget(name, amount) }
    fun addCategory(name: String, kind: String) = viewModelScope.launch { if (name.isNotBlank()) repo.addCategory(name, kind) }
    fun addHead(name: String, kind: String, icon: String) = viewModelScope.launch { if (name.isNotBlank()) repo.addCategory(name, kind, icon) }
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

    suspend fun isBackupEncrypted(uri: Uri): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val txt = app.contentResolver.openInputStream(uri)!!.use { readTextBounded(it) }
            BackupManager.isEncrypted(txt)
        }.getOrDefault(false)
    }

    suspend fun previewBackup(uri: Uri, passphrase: String?): BackupPreview =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val app = getApplication<Application>()
            val txt = app.contentResolver.openInputStream(uri)!!.use { readTextBounded(it) }
            BackupManager.preview(txt, passphrase)
        }

    fun restoreBackup(uri: Uri, passphrase: String?, done: (String) -> Unit) = viewModelScope.launch {
        val r = runCatching {
            val app = getApplication<Application>()
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.use { readTextBounded(it) }
            }
            // Always leave an app-private snapshot first. If a backup folder and passphrase
            // are configured, also save an encrypted copy there. Stop if either required
            // write fails, so a restore never happens without a recovery path.
            if (!BackupManager.makeSafetyCopy(app)) {
                throw IllegalStateException("Could not save a safety copy. Restore cancelled.")
            }
            if (BackupManager.backupToFolder(app) == BackupManager.BackupStatus.FAILED) {
                throw IllegalStateException("Encrypted safety backup failed. Restore cancelled.")
            }
            BackupManager.restore(AppDatabase.get(app), txt, passphrase)
        }
        done(r.fold(
            { "Restored $it transactions" },
            { "Restore failed: ${it.message ?: "check the backup file and passphrase"}" }
        ))
    }

    fun backupNow(done: (String) -> Unit) = viewModelScope.launch {
        val msg = when (BackupManager.backupToFolder(getApplication<Application>())) {
            BackupManager.BackupStatus.OK -> "Encrypted backup saved to your folder"
            BackupManager.BackupStatus.NO_FOLDER -> "Choose a backup folder first"
            BackupManager.BackupStatus.NO_PASSPHRASE -> "Set a backup passphrase first to encrypt backups"
            BackupManager.BackupStatus.FAILED -> "Backup failed"
        }
        done(msg)
    }

    /** Newest transaction that has a route, for "return trip / continue" suggestions. */
    fun lastTrip(): Txn? = txns.value.firstOrNull { it.routeFrom.isNotBlank() && it.routeTo.isNotBlank() }
    fun places(): List<String> = txns.value.flatMap { listOf(it.routeFrom, it.routeTo) }.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(30)
    fun usualRoutes(): List<Pair<String, String>> = txns.value.filter { it.routeFrom.isNotBlank() && it.routeTo.isNotBlank() }
        .groupingBy { it.routeFrom to it.routeTo }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(4)

    fun addPerson(name: String, done: (Long) -> Unit = {}) = viewModelScope.launch { if (name.isNotBlank()) done(repo.addPerson(name)) }

    fun deletePerson(id: Long) = viewModelScope.launch {
        val old = repo.personById(id)
        repo.deletePerson(id)
        if (old != null && old.photoUri.isNotBlank()) runCatching { File(getApplication<Application>().filesDir, old.photoUri).delete() }
    }

    fun savePerson(p: Person) = viewModelScope.launch {
        val old = if (p.id == 0L) null else repo.personById(p.id)
        repo.savePerson(p)
        if (old != null && old.photoUri != p.photoUri && old.photoUri.isNotBlank()) {
            runCatching { File(getApplication<Application>().filesDir, old.photoUri).delete() }
        }
    }

    /** Rare-action group split. Shares are written as normal ledger rows, so balances stay consistent. */
    fun saveGroup(
        name: String, note: String, members: List<Long>, total: Double,
        mode: String, weights: Map<Long, Double>, paidBy: Long, accountId: Long, date: Long,
        done: (Long) -> Unit = {}, fail: (String) -> Unit = {}
    ) = viewModelScope.launch {
        runCatching { repo.saveGroup(name, note, members, total, mode, weights, paidBy, accountId, date) }
            .onSuccess(done)
            .onFailure { fail(it.message ?: "Could not save the group.") }
    }

    fun deleteGroup(id: Long) = viewModelScope.launch { repo.deleteGroup(id) }

    fun settleGroupDebt(debt: GroupDebt, accountId: Long, done: () -> Unit = {}, fail: (String) -> Unit = {}) =
        viewModelScope.launch {
            runCatching { repo.settleGroupDebt(debt, accountId) }
                .onSuccess { done() }
                .onFailure { fail(it.message ?: "Could not settle this debt.") }
        }

    /** kind: GIVE (money leaves your account, they owe you more), RECEIVE (money comes in, they owe you less),
     *  THEY_PAID (they paid something for you; no account involved, you owe them more). */
    fun settle(personId: Long, kind: String, amount: Double, accountId: Long, note: String) = viewModelScope.launch {
        val t = when (kind) {
            "GIVE" -> Txn(type = "PERSON_OUT", amount = amount, note = note, date = today(), category = "People", accountId = accountId, personId = personId, owed = amount)
            "RECEIVE" -> Txn(type = "PERSON_IN", amount = amount, note = note, date = today(), category = "People", accountId = accountId, personId = personId, owed = -amount)
            else -> if (accountId != 0L)
                Txn(type = "PERSON_IN", amount = amount, note = note, date = today(), category = "People", accountId = accountId, personId = personId, owed = -amount)
            else Txn(type = "PERSON_ADJ", amount = amount, note = note, date = today(), category = "People", accountId = 0, personId = personId, owed = -amount)
        }
        repo.settlePerson(t)
    }

    // ---------- trips ----------

    fun saveTrip(
        id: Long, name: String, place: String, startDate: Long, endDate: Long,
        budget: Double, note: String, members: List<Long>,
        done: (Long) -> Unit = {}, fail: (String) -> Unit = {}
    ) = viewModelScope.launch {
        runCatching { repo.saveTrip(id, name, place, startDate, endDate, budget, note, members) }
            .onSuccess(done)
            .onFailure { fail(it.message ?: "Could not save the trip.") }
    }

    fun setTripArchived(id: Long, archived: Boolean) = viewModelScope.launch { repo.setTripArchived(id, archived) }
    fun deleteTrip(id: Long) = viewModelScope.launch { repo.deleteTrip(id) }

    fun addTripExpense(
        tripId: Long, title: String, amount: Double, payer: Long, participants: List<Long>,
        weights: Map<Long, Double>, accountId: Long, date: Long,
        done: () -> Unit = {}, fail: (String) -> Unit = {}
    ) = viewModelScope.launch {
        runCatching { repo.addTripExpense(tripId, title, amount, payer, participants, weights, accountId, date) }
            .onSuccess { done() }
            .onFailure { fail(it.message ?: "Could not save the expense.") }
    }

    fun settleTripDebt(debt: TripDebt, accountId: Long, note: String, done: () -> Unit = {}, fail: (String) -> Unit = {}) =
        viewModelScope.launch {
            runCatching { repo.settleTripDebt(debt, accountId, note) }
                .onSuccess { done() }
                .onFailure { fail(it.message ?: "Could not settle this debt.") }
        }
}
