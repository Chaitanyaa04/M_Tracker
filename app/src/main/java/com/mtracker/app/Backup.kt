package com.mtracker.app

import android.content.Context
import android.net.Uri
import android.util.Base64
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
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Human-readable summary shown before a destructive restore. */
data class BackupPreview(
    val exportedAt: Long,
    val accounts: Int,
    val transactions: Int,
    val persons: Int,
    val budgets: Int,
    val heads: Int,
    val recurring: Int,
    val pending: Int,
    val dayMarks: Int,
    val deleted: Int,
    val history: Int
)

private data class ParsedBackup(
    val exportedAt: Long,
    val accounts: List<Account>,
    val txns: List<Txn>,
    val persons: List<Person>,
    val budgets: List<Budget>,
    val cats: List<Category>,
    val recs: List<Recurring>,
    val pending: List<Pending>,
    val dayMarks: List<DayMark>,
    val deleted: List<DeletedTxn>,
    val history: List<ChangeHistory>
)

/** Full-fidelity local backup, including the local Recycle Bin and change history. */
object BackupManager {
    private const val KDF_ALG = "PBKDF2WithHmacSHA256"
    private const val KDF_ITER = 210_000
    private const val MIN_EPOCH_MS = 0L
    private const val MAX_EPOCH_MS = 4_102_444_800_000L // 2100-01-01 UTC
    private const val MAX_ACCOUNTS = 500
    private const val MAX_TXNS = 50_000
    private const val MAX_PERSONS = 5_000
    private const val MAX_OTHER = 2_000
    private const val MAX_HISTORY = 50_000

    private val TXN_TYPES = setOf("EXPENSE", "INCOME", "TRANSFER", "PERSON_OUT", "PERSON_IN", "PERSON_ADJ")
    private val REC_TYPES = setOf("EXPENSE", "INCOME", "TRANSFER")
    private val REC_FREQS = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    private val CAT_KINDS = setOf("EXPENSE", "INCOME")

    private fun deriveKey(passphrase: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iter, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance(KDF_ALG).generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun isEncrypted(rawJson: String): Boolean = runCatching {
        val o = JSONObject(rawJson)
        o.optString("app") in listOf("Arthak", "M_Tracker") && o.optBoolean("encrypted", false)
    }.getOrDefault(false)

    fun encrypt(plainJson: String, passphrase: String): String {
        require(passphrase.isNotBlank()) { "Backup passphrase is required." }
        val rng = SecureRandom()
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt, KDF_ITER)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("app", "Arthak")
            .put("encrypted", true)
            .put("v", 1)
            .put("kdf", KDF_ALG)
            .put("iter", KDF_ITER)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .toString()
    }

    fun decrypt(envelopeJson: String, passphrase: String): String {
        require(passphrase.isNotBlank()) { "Passphrase required to decrypt this backup." }
        val o = JSONObject(envelopeJson)
        require(o.optString("app") in listOf("Arthak", "M_Tracker") && o.optBoolean("encrypted", false)) {
            "Not an encrypted Arthak backup."
        }
        // Always use our own KDF cost; never trust an iteration count from an untrusted backup.
        val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(o.getString("ct"), Base64.NO_WRAP)
        require(salt.size >= 16 && iv.size == 12 && ct.size >= 16) { "Corrupted encrypted backup." }
        val key = deriveKey(passphrase, salt, KDF_ITER)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    suspend fun toJson(db: AppDatabase): String = withContext(Dispatchers.IO) {
        val d = db.dao()
        val accs = d.allAccounts(); val txns = d.allTxns(); val budgets = d.allBudgets()
        val cats = d.allCategories(); val recs = d.allRecurring(); val persons = d.allPersons()
        val pendings = d.allPending(); val marks = d.allDayMarks()
        val deleted = d.allDeletedTxns(); val history = d.allChangeHistory()
        JSONObject().apply {
            put("app", "Arthak"); put("schema", 4); put("exportedAt", System.currentTimeMillis())
            put("accounts", JSONArray().also { a -> accs.forEach {
                a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("opening", it.openingBalance).put("color", it.color)
                    .put("archived", it.archived).put("default", it.isDefault))
            } })
            put("transactions", JSONArray().also { a -> txns.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("date", it.date).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget).put("created", it.createdAt)
                    .put("person", it.personId).put("owed", it.owed).put("from", it.routeFrom).put("toPlace", it.routeTo).put("items", it.items))
            } })
            put("persons", JSONArray().also { a -> persons.forEach {
                a.put(JSONObject().put("id", it.id).put("name", it.name).put("rel", it.relation).put("phone", it.phone).put("note", it.note))
            } })
            put("budgets", JSONArray().also { a -> budgets.forEach { a.put(JSONObject().put("name", it.name).put("amount", it.amount)) } })
            put("categories", JSONArray().also { a -> cats.forEach {
                a.put(JSONObject().put("name", it.name).put("kind", it.kind).put("icon", it.icon))
            } })
            put("recurring", JSONArray().also { a -> recs.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget)
                    .put("freq", it.freq).put("nextDue", it.nextDue))
            } })
            put("pending", JSONArray().also { a -> pendings.forEach {
                a.put(JSONObject().put("id", it.id).put("time", it.time).put("pkg", it.pkg).put("label", it.label)
                    .put("account", it.accountId).put("amount", it.amount ?: JSONObject.NULL).put("merchant", it.merchant ?: JSONObject.NULL))
            } })
            put("daymarks", JSONArray().also { a -> marks.forEach { a.put(JSONObject().put("date", it.date)) } })
            put("deleted", JSONArray().also { a -> deleted.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("date", it.date).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget).put("created", it.createdAt)
                    .put("person", it.personId).put("owed", it.owed).put("from", it.routeFrom).put("toPlace", it.routeTo).put("items", it.items)
                    .put("deletedAt", it.deletedAt))
            } })
            put("history", JSONArray().also { a -> history.forEach {
                a.put(JSONObject().put("id", it.id).put("entity", it.entity).put("entityId", it.entityId)
                    .put("action", it.action).put("payload", it.payload).put("changedAt", it.changedAt))
            } })
        }.toString()
    }

    /** Parses and fully validates a backup without changing the database. */
    private fun parse(rawJson: String, passphrase: String?): ParsedBackup {
        val json = if (isEncrypted(rawJson)) decrypt(rawJson, passphrase.orEmpty()) else rawJson
        val o = JSONObject(json)
        require(o.optString("app") in listOf("Arthak", "M_Tracker")) { "Not an Arthak backup." }
        require(o.optInt("schema", 2) in 2..4) { "Unsupported backup version." }

        fun JSONObject.toOrNull(k: String): Long? = if (isNull(k)) null else getLong(k)
        fun JSONObject.doubleOrNull(k: String): Double? = if (isNull(k)) null else getDouble(k)
        fun requireFinite(v: Double, label: String): Double {
            require(v.isFinite()) { "Invalid $label in backup." }
            return v
        }
        fun accountType(raw: String): String {
            val mapped = if (raw.equals("ASSET", true)) "BANK" else raw.trim().uppercase()
            require(mapped.matches(Regex("[A-Z][A-Z0-9_]{2,23}"))) { "Invalid account type." }
            return mapped
        }

        val accArr = o.getJSONArray("accounts")
        val txnArr = o.getJSONArray("transactions")
        val personArr = o.optJSONArray("persons") ?: JSONArray()
        val budgetArr = o.getJSONArray("budgets")
        val catArr = o.getJSONArray("categories")
        val recArr = o.getJSONArray("recurring")
        val pendingArr = o.optJSONArray("pending") ?: JSONArray()
        val markArr = o.optJSONArray("daymarks") ?: JSONArray()
        val deletedArr = o.optJSONArray("deleted") ?: JSONArray()
        val historyArr = o.optJSONArray("history") ?: JSONArray()

        require(accArr.length() in 1..MAX_ACCOUNTS) { "Invalid account count in backup." }
        require(txnArr.length() <= MAX_TXNS) { "Backup exceeds maximum transaction count." }
        require(personArr.length() <= MAX_PERSONS) { "Backup exceeds maximum people count." }
        require(budgetArr.length() <= MAX_OTHER && catArr.length() <= MAX_OTHER && recArr.length() <= MAX_OTHER &&
            pendingArr.length() <= MAX_OTHER && markArr.length() <= MAX_PERSONS) { "Backup exceeds allowed metadata limits." }
        require(deletedArr.length() <= MAX_TXNS && historyArr.length() <= MAX_HISTORY) { "Backup exceeds local history limits." }

        val accs = (0 until accArr.length()).map { accArr.getJSONObject(it) }.map {
            val name = it.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 100) { "Invalid account name." }
            Account(
                it.getLong("id"), name, accountType(it.getString("type")),
                requireFinite(it.getDouble("opening"), "openingBalance"), it.optInt("color", 0),
                it.optBoolean("archived", false), it.optBoolean("default", false)
            )
        }
        val accountIds = accs.map { it.id }.toSet()
        require(accs.count { it.isDefault } <= 1) { "Backup has more than one default account." }

        val persons = (0 until personArr.length()).map { personArr.getJSONObject(it) }.map {
            val name = it.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 100) { "Invalid person name." }
            Person(it.getLong("id"), name, it.optString("rel", "").take(50), it.optString("phone", "").take(24), it.optString("note", "").take(300))
        }

        val txns = (0 until txnArr.length()).map { txnArr.getJSONObject(it) }.map {
            val type = it.getString("type").trim().uppercase()
            require(type in TXN_TYPES) { "Invalid transaction type." }
            val amt = requireFinite(it.getDouble("amount"), "amount")
            require(amt >= 0.0) { "Negative transaction amount." }
            val owed = requireFinite(it.optDouble("owed", 0.0), "owed")
            require(owed >= 0.0) { "Negative owed amount." }
            require(type != "EXPENSE" || owed <= amt) { "Owed exceeds expense amount." }
            val date = it.getLong("date")
            require(date in MIN_EPOCH_MS..MAX_EPOCH_MS) { "Transaction date out of valid range." }
            val accId = it.getLong("account")
            require(accId in accountIds || (type == "PERSON_ADJ" && accId == 0L)) { "Transaction references unknown account." }
            val toId = it.toOrNull("to")
            if (toId != null) require(toId in accountIds) { "Transfer references unknown destination account." }
            Txn(
                it.getLong("id"), type, amt, it.getString("merchant").take(120), it.getString("note").take(500), date,
                it.getString("category").take(80), accId, toId, it.getString("tag").take(60), it.getBoolean("exclude"),
                it.optLong("created", 0).coerceIn(0L, MAX_EPOCH_MS), it.optLong("person", 0), owed,
                it.optString("from", "").take(100), it.optString("toPlace", "").take(100), it.optString("items", "").take(8_000)
            )
        }

        val budgets = (0 until budgetArr.length()).map { budgetArr.getJSONObject(it) }.map {
            val amt = requireFinite(it.getDouble("amount"), "budget")
            require(amt > 0.0) { "Budget amount must be positive." }
            Budget(it.getString("name").take(80), amt)
        }

        val cats = (0 until catArr.length()).map { catArr.getJSONObject(it) }.map {
            val kind = it.getString("kind").trim().uppercase()
            require(kind in CAT_KINDS) { "Invalid category kind." }
            Category(it.getString("name").take(80), kind, it.optString("icon", "").take(32))
        }

        val recs = (0 until recArr.length()).map { recArr.getJSONObject(it) }.map {
            val type = it.getString("type").trim().uppercase()
            val freq = it.getString("freq").trim().uppercase()
            require(type in REC_TYPES) { "Invalid recurring type." }
            require(freq in REC_FREQS) { "Invalid recurring frequency." }
            val amt = requireFinite(it.getDouble("amount"), "recurring amount")
            require(amt > 0.0) { "Recurring amount must be positive." }
            val accId = it.getLong("account")
            require(accId in accountIds) { "Recurring entry references unknown account." }
            val toId = it.toOrNull("to")
            if (toId != null) require(toId in accountIds) { "Recurring transfer references unknown account." }
            val nextDue = it.getLong("nextDue")
            require(nextDue in 946_684_800_000L..MAX_EPOCH_MS) { "Recurring nextDue out of valid range." }
            Recurring(it.getLong("id"), type, amt, it.getString("merchant").take(120), it.getString("note").take(500),
                it.getString("category").take(80), accId, toId, it.getString("tag").take(60), it.getBoolean("exclude"), freq, nextDue)
        }

        val pending = (0 until pendingArr.length()).map { pendingArr.getJSONObject(it) }.map {
            val time = it.getLong("time")
            require(time in MIN_EPOCH_MS..MAX_EPOCH_MS) { "Pending time out of valid range." }
            val amount = it.doubleOrNull("amount")?.let { v -> requireFinite(v, "pending amount") }
            require(amount == null || amount >= 0.0) { "Negative pending amount." }
            val accId = it.getLong("account")
            require(accId == 0L || accId in accountIds) { "Pending prompt references unknown account." }
            Pending(it.getLong("id"), time, it.getString("pkg").take(120), it.getString("label").take(160),
                accId, amount, it.optString("merchant", "").take(120).ifBlank { null })
        }

        val marks = (0 until markArr.length()).map { markArr.getJSONObject(it) }.map {
            val date = it.getLong("date")
            require(date in MIN_EPOCH_MS..MAX_EPOCH_MS) { "Day mark out of valid range." }
            DayMark(date)
        }

        val deleted = (0 until deletedArr.length()).map { deletedArr.getJSONObject(it) }.map {
            val type = it.getString("type").trim().uppercase()
            require(type in TXN_TYPES) { "Invalid recycled transaction type." }
            val amt = requireFinite(it.getDouble("amount"), "recycled amount")
            require(amt >= 0.0) { "Negative recycled amount." }
            val owed = requireFinite(it.optDouble("owed", 0.0), "recycled owed")
            require(owed >= 0.0) { "Negative recycled owed amount." }
            require(type != "EXPENSE" || owed <= amt) { "Recycled owed exceeds expense amount." }
            val date = it.getLong("date")
            require(date in MIN_EPOCH_MS..MAX_EPOCH_MS) { "Recycled transaction date out of range." }
            val accId = it.getLong("account")
            require(accId in accountIds || (type == "PERSON_ADJ" && accId == 0L)) { "Recycled transaction references unknown account." }
            val toId = it.toOrNull("to")
            if (toId != null) require(toId in accountIds) { "Recycled transfer references unknown destination." }
            val deletedAt = it.getLong("deletedAt")
            require(deletedAt in MIN_EPOCH_MS..MAX_EPOCH_MS) { "Recycle timestamp out of range." }
            DeletedTxn(
                it.getLong("id"), type, amt, it.getString("merchant").take(120), it.getString("note").take(500), date,
                it.getString("category").take(80), accId, toId, it.getString("tag").take(60), it.getBoolean("exclude"),
                it.optLong("created", 0).coerceIn(0L, MAX_EPOCH_MS), it.optLong("person", 0),
                owed,
                it.optString("from", "").take(100), it.optString("toPlace", "").take(100), it.optString("items", "").take(8_000), deletedAt
            )
        }

        val history = (0 until historyArr.length()).map { historyArr.getJSONObject(it) }.map {
            val entity = it.getString("entity").take(30)
            val action = it.getString("action").take(30)
            require(entity.isNotBlank() && action.isNotBlank()) { "Invalid history entry." }
            val changedAt = it.getLong("changedAt")
            require(changedAt in MIN_EPOCH_MS..MAX_EPOCH_MS) { "History timestamp out of range." }
            ChangeHistory(it.getLong("id"), entity, it.getLong("entityId"), action, it.getString("payload").take(8_000), changedAt)
        }

        fun requireUnique(ids: List<Long>, label: String) {
            require(ids.size == ids.toSet().size) { "Duplicate $label id in backup." }
        }
        requireUnique(accs.map { it.id }, "account")
        requireUnique(txns.map { it.id }, "transaction")
        requireUnique(persons.map { it.id }, "person")
        requireUnique(recs.map { it.id }, "recurring")
        requireUnique(pending.map { it.id }, "pending")
        requireUnique(deleted.map { it.id }, "recycled transaction")
        requireUnique(history.map { it.id }, "history")
        require(budgets.map { it.name }.toSet().size == budgets.size) { "Duplicate budget name in backup." }
        require(cats.map { it.name to it.kind }.toSet().size == cats.size) { "Duplicate head in backup." }
        require(marks.map { it.date }.toSet().size == marks.size) { "Duplicate day mark in backup." }
        require((txns.map { it.id }.toSet() intersect deleted.map { it.id }.toSet()).isEmpty()) {
            "Transaction id is also used in the recycle bin."
        }

        return ParsedBackup(o.optLong("exportedAt", 0L), accs, txns, persons, budgets, cats, recs, pending, marks, deleted, history)
    }

    /** Validate and summarise before asking the user to confirm a restore. */
    fun preview(rawJson: String, passphrase: String? = null): BackupPreview {
        val b = parse(rawJson, passphrase)
        return BackupPreview(b.exportedAt, b.accounts.size, b.txns.size, b.persons.size,
            b.budgets.size, b.cats.size, b.recs.size, b.pending.size, b.dayMarks.size,
            b.deleted.size, b.history.size)
    }

    /** Replaces everything with the backup's contents. Supports encrypted and legacy plaintext backups. */
    suspend fun restore(db: AppDatabase, rawJson: String, passphrase: String? = null): Int = withContext(Dispatchers.IO) {
        val b = parse(rawJson, passphrase)
        val d = db.dao()
        db.withTransaction {
            d.clearTxns(); d.clearPersons(); d.clearRecurring(); d.clearAccounts(); d.clearBudgets()
            d.clearCategories(); d.clearPending(); d.clearDayMarks(); d.clearDeletedTxnsAll(); d.clearHistory()
            b.accounts.forEach { d.insertAccount(it) }
            b.txns.forEach { d.insert(it) }
            b.budgets.forEach { d.upsertBudget(it) }
            b.cats.forEach { d.insertCategory(it) }
            b.recs.forEach { d.insertRecurring(it) }
            b.persons.forEach { d.insertPerson(it) }
            b.pending.forEach { d.insertPending(it) }
            b.dayMarks.forEach { d.insertMark(it) }
            b.deleted.forEach { d.insertDeletedTxn(it) }
            b.history.forEach { d.insertChange(it) }
        }
        b.txns.size
    }

    /**
     * App-private snapshot taken before every restore. It does not depend on a folder or
     * passphrase, so a restore can never replace data without leaving a recovery path.
     * Only the newest three snapshots are kept, and only files with our prefix are pruned.
     */
    suspend fun makeSafetyCopy(c: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(c.filesDir, "restore-safety").apply { mkdirs() }
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now())
            val file = File(dir, "before_restore_${stamp}_${System.currentTimeMillis() % 1000}.json")
            file.writeText(toJson(AppDatabase.get(c)), Charsets.UTF_8)
            val snapshots = dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("before_restore_") && it.name.endsWith(".json") }
                .sortedByDescending { it.lastModified() }
            snapshots.drop(3).forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    enum class BackupStatus { OK, NO_FOLDER, NO_PASSPHRASE, FAILED }

    suspend fun backupToFolder(c: Context): BackupStatus = withContext(Dispatchers.IO) {
        val p = AppPrefs.get(c)
        val uriS = p.backupUri.value
        if (uriS.isBlank()) return@withContext BackupStatus.NO_FOLDER
        val pass = p.getBackupPassphrase() ?: return@withContext BackupStatus.NO_PASSPHRASE
        try {
            val dir = DocumentFile.fromTreeUri(c, Uri.parse(uriS)) ?: return@withContext BackupStatus.FAILED
            val plainJson = toJson(AppDatabase.get(c))
            val encryptedJson = encrypt(plainJson, pass)
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now())
            val name = "arthak_backup_${stamp}_${System.currentTimeMillis() % 1000}.json"
            val f = dir.createFile("application/json", name) ?: return@withContext BackupStatus.FAILED
            c.contentResolver.openOutputStream(f.uri, "w")?.use { it.write(encryptedJson.toByteArray(Charsets.UTF_8)) }
                ?: return@withContext BackupStatus.FAILED
            // Only prune files this app created and tracked — never touch unrelated user files.
            p.addBackupFile(f.uri.toString())
            p.pruneBackupFiles().forEach { tracked ->
                DocumentFile.fromSingleUri(c, Uri.parse(tracked))?.delete()
            }
            p.setLastBackup(System.currentTimeMillis())
            BackupStatus.OK
        } catch (e: Exception) {
            BackupStatus.FAILED
        }
    }
}

class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val status = BackupManager.backupToFolder(applicationContext)
        if (status != BackupManager.BackupStatus.OK) ReminderNotifier.backupFailed(applicationContext)
        return if (status == BackupManager.BackupStatus.OK) Result.success() else Result.failure()
    }
}

object BackupScheduler {
    private const val NAME = "auto-backup"
    fun sync(c: Context) {
        val wm = WorkManager.getInstance(c)
        val p = AppPrefs.get(c)
        if (p.autoBackup.value && p.backupUri.value.isNotBlank() && p.hasBackupPassphrase.value) {
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.REPLACE,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
            )
        } else wm.cancelUniqueWork(NAME)
    }
}
