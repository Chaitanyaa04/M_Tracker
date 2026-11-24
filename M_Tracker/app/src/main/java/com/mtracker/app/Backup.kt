package com.mtracker.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Full-fidelity backup (accounts, ledger, budgets, categories, repeats) as one JSON file. */
object BackupManager {
    suspend fun toJson(db: AppDatabase): String = withContext(Dispatchers.IO) {
        val d = db.dao()
        val accs = d.allAccounts(); val txns = d.allTxns(); val budgets = d.allBudgets()
        val cats = d.allCategories(); val recs = d.allRecurring()
        JSONObject().apply {
            put("app", "M_Tracker"); put("schema", 2); put("exportedAt", System.currentTimeMillis())
            put("accounts", JSONArray().also { a -> accs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("opening", it.openingBalance)) } })
            put("transactions", JSONArray().also { a -> txns.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("date", it.date).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget))
            } })
            put("budgets", JSONArray().also { a -> budgets.forEach { a.put(JSONObject().put("name", it.name).put("amount", it.amount)) } })
            put("categories", JSONArray().also { a -> cats.forEach { a.put(JSONObject().put("name", it.name).put("kind", it.kind)) } })
            put("recurring", JSONArray().also { a -> recs.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget)
                    .put("freq", it.freq).put("nextDue", it.nextDue))
            } })
        }.toString()
    }

    /** Replaces everything with the backup's contents. Returns number of transactions restored. */
    suspend fun restore(db: AppDatabase, json: String): Int = withContext(Dispatchers.IO) {
        val o = JSONObject(json)
        require(o.optString("app") == "M_Tracker")
        fun JSONObject.toOrNull(k: String): Long? = if (isNull(k)) null else getLong(k)
        val accs = o.getJSONArray("accounts").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Account(it.getLong("id"), it.getString("name"), it.getString("type"), it.getDouble("opening")) } }
        val txns = o.getJSONArray("transactions").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Txn(it.getLong("id"), it.getString("type"), it.getDouble("amount"), it.getString("merchant"), it.getString("note"), it.getLong("date"),
                it.getString("category"), it.getLong("account"), it.toOrNull("to"), it.getString("tag"), it.getBoolean("exclude"))
        } }
        val budgets = o.getJSONArray("budgets").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Budget(it.getString("name"), it.getDouble("amount")) } }
        val cats = o.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Category(it.getString("name"), it.getString("kind")) } }
        val recs = o.getJSONArray("recurring").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Recurring(it.getLong("id"), it.getString("type"), it.getDouble("amount"), it.getString("merchant"), it.getString("note"),
                it.getString("category"), it.getLong("account"), it.toOrNull("to"), it.getString("tag"), it.getBoolean("exclude"),
                it.getString("freq"), it.getLong("nextDue"))
        } }
        val d = db.dao()
        db.withTransaction {
            d.clearTxns(); d.clearRecurring(); d.clearAccounts(); d.clearBudgets(); d.clearCategories()
            accs.forEach { d.insertAccount(it) }
            txns.forEach { d.insert(it) }
            budgets.forEach { d.upsertBudget(it) }
            cats.forEach { d.insertCategory(it) }
            recs.forEach { d.insertRecurring(it) }
        }
        txns.size
    }

    suspend fun backupToFolder(c: Context): Boolean = withContext(Dispatchers.IO) {
        val p = AppPrefs.get(c)
        val uriS = p.backupUri.value
        if (uriS.isBlank()) return@withContext false
        try {
            val dir = DocumentFile.fromTreeUri(c, Uri.parse(uriS)) ?: return@withContext false
            val json = toJson(AppDatabase.get(c))
            val name = "m_tracker_backup_${LocalDate.now()}.json"
            dir.findFile(name)?.delete()
            val f = dir.createFile("application/json", name) ?: return@withContext false
            c.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(json.toByteArray()) }
            dir.listFiles().filter { it.name?.startsWith("m_tracker_backup_") == true }
                .sortedByDescending { it.name }.drop(8).forEach { it.delete() }
            p.setLastBackup(System.currentTimeMillis())
            true
        } catch (e: Exception) { false }
    }
}

class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        BackupManager.backupToFolder(applicationContext)
        return Result.success()
    }
}

object BackupScheduler {
    private const val NAME = "auto-backup"
    fun sync(c: Context) {
        val wm = WorkManager.getInstance(c)
        val p = AppPrefs.get(c)
        if (p.autoBackup.value && p.backupUri.value.isNotBlank()) {
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build())
        } else wm.cancelUniqueWork(NAME)
    }
}
