package com.mtracker.app

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class Repository(private val db: AppDatabase) {
    private val d = db.dao()
    val txns = d.transactions()
    val accounts = d.accounts()
    val budgets = d.budgets()
    val pending = d.pending()
    val recurring = d.recurring()
    val categories = d.categories()
    val dayMarks = d.dayMarks()
    val persons = d.persons()
    val deletedTxns = d.deletedTxns()
    val changeHistory = d.changeHistory()

    private fun Txn.asJson() = JSONObject()
        .put("id", id).put("type", type).put("amount", amount).put("merchant", merchant)
        .put("note", note).put("date", date).put("category", category).put("account", accountId)
        .put("to", toAccountId ?: JSONObject.NULL).put("tag", tag).put("exclude", excludeFromBudget)
        .put("created", createdAt).put("person", personId).put("owed", owed)
        .put("from", routeFrom).put("toPlace", routeTo).put("items", items)

    private fun Txn.asDeleted() = DeletedTxn(
        id = id, type = type, amount = amount, merchant = merchant, note = note, date = date,
        category = category, accountId = accountId, toAccountId = toAccountId, tag = tag,
        excludeFromBudget = excludeFromBudget, createdAt = createdAt, personId = personId,
        owed = owed, routeFrom = routeFrom, routeTo = routeTo, items = items,
        deletedAt = System.currentTimeMillis()
    )

    private fun DeletedTxn.asTxn() = Txn(
        id = id, type = type, amount = amount, merchant = merchant, note = note, date = date,
        category = category, accountId = accountId, toAccountId = toAccountId, tag = tag,
        excludeFromBudget = excludeFromBudget, createdAt = createdAt, personId = personId,
        owed = owed, routeFrom = routeFrom, routeTo = routeTo, items = items
    )

    private suspend fun record(entity: String, id: Long, action: String, payload: String) {
        d.insertChange(ChangeHistory(entity = entity, entityId = id, action = action, payload = payload, changedAt = System.currentTimeMillis()))
    }

    /** Restoring a recycled txn must never leave it pointing at a missing or hidden account. */
    private suspend fun ensureAccount(id: Long) {
        if (id == 0L) return
        if (d.allAccounts().none { it.id == id }) {
            d.insertAccount(Account(id = id, name = "Restored account", type = "BANK"))
        } else {
            d.unarchiveAccount(id)
        }
    }

    suspend fun save(t: Txn) {
        if (t.id == 0L) {
            d.insert(if (t.createdAt == 0L) t.copy(createdAt = System.currentTimeMillis()) else t)
        } else {
            val old = d.txnById(t.id)
            if (old != null && old != t) record("TXN", t.id, "EDIT", old.asJson().toString())
            val keepCreated = if (t.createdAt == 0L) old?.createdAt ?: System.currentTimeMillis() else t.createdAt
            d.update(t.copy(createdAt = keepCreated))
        }
    }

    /** Undo after a swipe-delete: put the row back and clear its Recycle Bin entry. */
    suspend fun restore(t: Txn) = db.withTransaction {
        ensureAccount(t.accountId)
        t.toAccountId?.let { ensureAccount(it) }
        d.deleteDeletedTxn(t.id)
        d.insert(t)
    }

    suspend fun delete(t: Txn) = db.withTransaction {
        d.insertDeletedTxn(t.asDeleted())
        record("TXN", t.id, "DELETE", t.asJson().toString())
        d.delete(t)
    }

    suspend fun restoreDeleted(item: DeletedTxn) = db.withTransaction {
        val t = item.asTxn()
        ensureAccount(t.accountId)
        t.toAccountId?.let { ensureAccount(it) }
        d.insert(t)
        d.deleteDeletedTxn(item.id)
        record("TXN", item.id, "RESTORE", t.asJson().toString())
    }

    suspend fun deleteDeletedForever(id: Long) {
        d.deleteDeletedTxn(id)
        record("TXN", id, "DELETE_FOREVER", "")
    }

    suspend fun seedDefaultHeads() {
        DEFAULT_EXPENSE_CATEGORIES.forEach { d.insertCategory(Category(it, "EXPENSE", defaultHeadIcon(it, "EXPENSE"))) }
        DEFAULT_INCOME_CATEGORIES.forEach { d.insertCategory(Category(it, "INCOME", defaultHeadIcon(it, "INCOME"))) }
    }

    suspend fun saveAccount(a: Account) = db.withTransaction {
        if (a.isDefault) d.clearDefaultAccount()
        if (a.id == 0L) d.insertAccount(a) else d.updateAccount(a)
    }

    suspend fun deleteAccount(id: Long) = db.withTransaction {
        d.allTxns().filter { it.accountId == id || it.toAccountId == id }.forEach { t ->
            d.insertDeletedTxn(t.asDeleted())
            record("TXN", t.id, "DELETE", t.asJson().toString())
        }
        d.deleteTxnsOfAccount(id)
        d.deleteRecurringOfAccount(id)
        d.deletePendingOfAccount(id)
        // Keep the account row so recycled transactions can still point at it.
        d.archiveAccount(id)
        record("ACCOUNT", id, "ARCHIVE", JSONObject().put("id", id).toString())
    }

    suspend fun setBudget(name: String, amount: Double) {
        if (amount <= 0) d.deleteBudget(name) else d.upsertBudget(Budget(name, amount))
    }

    suspend fun saveWithRepeat(t: Txn, repeat: String, pendingId: Long) = db.withTransaction {
        save(t)
        if (t.id == 0L && repeat != "NEVER") d.insertRecurring(
            Recurring(
                type = t.type, amount = t.amount, merchant = t.merchant, note = t.note, category = t.category,
                accountId = t.accountId, toAccountId = t.toAccountId, tag = t.tag,
                excludeFromBudget = t.excludeFromBudget, freq = repeat, nextDue = nextDate(t.date, repeat)
            )
        )
        if (pendingId != 0L) d.deletePending(pendingId)
    }

    /** Creates every repeating transaction that has come due (catches up if the app wasn't opened). */
    suspend fun materializeDue(): Int = db.withTransaction {
        val td = today()
        var n = 0
        for (r in d.allRecurring()) {
            var due = r.nextDue
            var guard = 0
            while (due <= td && guard++ < 400) {
                d.insert(
                    Txn(
                        type = r.type, amount = r.amount, merchant = r.merchant, note = r.note, date = due,
                        category = r.category, accountId = r.accountId, toAccountId = r.toAccountId,
                        tag = r.tag, excludeFromBudget = r.excludeFromBudget,
                        createdAt = System.currentTimeMillis()
                    )
                )
                n++
                due = nextDate(due, r.freq)
            }
            if (due != r.nextDue) d.updateRecurring(r.copy(nextDue = due))
        }
        n
    }

    suspend fun deleteRecurring(id: Long) = d.deleteRecurring(id)
    suspend fun deletePending(id: Long) = d.deletePending(id)
    suspend fun addCategory(name: String, kind: String, icon: String = "") = d.insertCategory(Category(name.trim(), kind, icon))
    suspend fun deleteCategory(name: String, kind: String) = d.deleteCategory(name, kind)
    suspend fun markNoSpend(day: Long) = d.insertMark(DayMark(day))
    suspend fun addPerson(name: String): Long = d.insertPerson(Person(name = name.trim()))
    suspend fun deletePerson(id: Long) = db.withTransaction {
        d.allPersons().firstOrNull { it.id == id }?.let { p ->
            record("PERSON", id, "DELETE", JSONObject().put("id", p.id).put("name", p.name).toString())
        }
        d.allTxns().filter { it.personId == id }.forEach { t ->
            record("TXN", t.id, "PERSON_DETACH", t.asJson().toString())
        }
        d.detachPerson(id); d.deletePerson(id)
    }
    suspend fun addTxn(t: Txn) { save(t) }
    suspend fun savePerson(p: Person): Long = if (p.id == 0L) d.insertPerson(p) else { d.updatePerson(p); p.id }

    // ---------- CSV (Storage Access Framework URIs; no storage permission) ----------
    private val header = "date,type,amount,merchant,note,category,account,to_account,tag,exclude_from_budget"
    private val zone get() = ZoneId.systemDefault()
    private val formulaTriggers = setOf('=', '+', '-', '@', '\t', '\r')

    private fun safeTextCell(s: String): String {
        // Skip leading whitespace and Unicode format chars (BOM, zero-width, etc.)
        // so `=`, `+`, `-`, `@`, tab or CR can't hide behind them in spreadsheets.
        val first = s.firstOrNull { ch -> Character.getType(ch) != Character.FORMAT.toInt() && !ch.isWhitespace() }
        val guarded = if (first != null && first in formulaTriggers) "'$s" else s
        return q(guarded)
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    suspend fun exportCsv(cr: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val accs = d.allAccounts().associateBy { it.id }
        val list = d.allTxns().sortedWith(compareBy({ it.date }, { it.id }))
        cr.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
            w.write(header + "\n")
            for (t in list) {
                val dateStr = java.time.Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate().toString()
                val amountStr = if (t.amount.isFinite()) t.amount.toString() else "0.0"
                val line = listOf(
                    q(dateStr),
                    q(t.type),
                    q(amountStr),
                    safeTextCell(t.merchant),
                    safeTextCell(t.note),
                    safeTextCell(t.category),
                    safeTextCell(accs[t.accountId]?.name ?: ""),
                    safeTextCell(t.toAccountId?.let { accs[it]?.name } ?: ""),
                    safeTextCell(t.tag),
                    q(t.excludeFromBudget.toString())
                ).joinToString(",")
                w.write(line + "\n")
            }
        }
        list.size
    }

    suspend fun importCsv(cr: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = cr.openInputStream(uri)!!.use { readTextBounded(it) }.removePrefix("\uFEFF")
        val rows = parseCsv(text).drop(1).filter { it.size >= 10 }.take(50_000)
        val accs = d.allAccounts().associateBy { it.name.lowercase() }.toMutableMap()
        var n = 0
        db.withTransaction {
            suspend fun accId(name: String): Long {
                val clean = name.trim().take(100)
                val key = clean.lowercase()
                accs[key]?.let { return it.id }
                val id = d.insertAccount(Account(name = clean, type = "BANK"))
                accs[key] = Account(id, clean, "BANK")
                return id
            }
            for (r in rows) {
                val amt = r[2].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
                val date = try { LocalDate.parse(r[0].trim()).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e: Exception) { continue }
                val type = r[1].trim().uppercase()
                if (type !in listOf("EXPENSE", "INCOME", "TRANSFER") || r[6].isBlank()) continue
                d.insert(
                    Txn(
                        type = type, amount = amt,
                        merchant = r[3].take(120), note = r[4].take(500),
                        category = r[5].ifBlank { "Other" }.take(80), date = date,
                        accountId = accId(r[6]),
                        toAccountId = if (r[7].isNotBlank()) accId(r[7]) else null,
                        tag = r[8].take(60), excludeFromBudget = r[9].trim().equals("true", true)
                    )
                )
                if (type != "TRANSFER") d.insertCategory(Category(r[5].ifBlank { "Other" }.take(80), if (type == "INCOME") "INCOME" else "EXPENSE"))
                n++
            }
        }
        n
    }
}

fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val sb = StringBuilder()
    var inQ = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inQ) {
            if (ch == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
            } else sb.append(ch)
        } else {
            when (ch) {
                '"' -> inQ = true
                ',' -> { row.add(sb.toString()); sb.clear() }
                '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = mutableListOf() }
                '\r' -> {}
                else -> sb.append(ch)
            }
        }
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
    return rows
}

fun nextDate(ms: Long, freq: String): Long {
    val z = ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
    val n = when (freq) {
        "DAILY" -> d.plusDays(1)
        "WEEKLY" -> d.plusWeeks(1)
        "YEARLY" -> d.plusYears(1)
        else -> d.plusMonths(1)
    }
    return n.atStartOfDay(z).toInstant().toEpochMilli()
}
