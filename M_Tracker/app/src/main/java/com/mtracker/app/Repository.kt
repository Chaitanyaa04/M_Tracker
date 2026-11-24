package com.mtracker.app

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun save(t: Txn) { if (t.id == 0L) d.insert(t) else d.update(t) }
    suspend fun restore(t: Txn) { d.insert(t) }
    suspend fun delete(t: Txn) = d.delete(t)

    suspend fun saveAccount(a: Account) { if (a.id == 0L) d.insertAccount(a) else d.updateAccount(a) }
    suspend fun deleteAccount(id: Long) = db.withTransaction {
        d.deleteTxnsOfAccount(id); d.deleteRecurringOfAccount(id); d.deleteAccount(id)
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
                        tag = r.tag, excludeFromBudget = r.excludeFromBudget
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
    suspend fun addCategory(name: String, kind: String) = d.insertCategory(Category(name.trim(), kind))
    suspend fun deleteCategory(name: String, kind: String) = d.deleteCategory(name, kind)
    suspend fun markNoSpend(day: Long) = d.insertMark(DayMark(day))

    // ---------- CSV (Storage Access Framework URIs; no storage permission) ----------
    private val header = "date,type,amount,merchant,note,category,account,to_account,tag,exclude_from_budget"
    private val zone get() = ZoneId.systemDefault()

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    suspend fun exportCsv(cr: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val accs = d.allAccounts().associateBy { it.id }
        val list = d.allTxns().sortedWith(compareBy({ it.date }, { it.id }))
        cr.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
            w.write(header + "\n")
            for (t in list) {
                val cols = listOf(
                    java.time.Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate().toString(),
                    t.type, t.amount.toString(), t.merchant, t.note, t.category,
                    accs[t.accountId]?.name ?: "",
                    t.toAccountId?.let { accs[it]?.name } ?: "",
                    t.tag, t.excludeFromBudget.toString()
                )
                w.write(cols.joinToString(",") { q(it) } + "\n")
            }
        }
        list.size
    }

    suspend fun importCsv(cr: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = cr.openInputStream(uri)!!.bufferedReader().use { it.readText() }.removePrefix("\uFEFF")
        val rows = parseCsv(text).drop(1).filter { it.size >= 10 }
        val accs = d.allAccounts().associateBy { it.name.lowercase() }.toMutableMap()
        var n = 0
        db.withTransaction {
            suspend fun accId(name: String): Long {
                val key = name.trim().lowercase()
                accs[key]?.let { return it.id }
                val id = d.insertAccount(Account(name = name.trim(), type = "BANK"))
                accs[key] = Account(id, name.trim(), "BANK")
                return id
            }
            for (r in rows) {
                val amt = r[2].toDoubleOrNull() ?: continue
                val date = try { LocalDate.parse(r[0].trim()).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e: Exception) { continue }
                val type = r[1].trim().uppercase()
                if (type !in listOf("EXPENSE", "INCOME", "TRANSFER") || r[6].isBlank()) continue
                d.insert(
                    Txn(
                        type = type, amount = amt, merchant = r[3], note = r[4],
                        category = r[5].ifBlank { "Other" }, date = date,
                        accountId = accId(r[6]),
                        toAccountId = if (r[7].isNotBlank()) accId(r[7]) else null,
                        tag = r[8], excludeFromBudget = r[9].trim().equals("true", true)
                    )
                )
                if (type != "TRANSFER") d.insertCategory(Category(r[5].ifBlank { "Other" }, if (type == "INCOME") "INCOME" else "EXPENSE"))
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
