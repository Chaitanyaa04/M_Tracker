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
import java.security.SecureRandom
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Full-fidelity backup (accounts, ledger, budgets, categories, repeats) as one AES-256-GCM encrypted JSON file. */
object BackupManager {
    private const val KDF_ALG = "PBKDF2WithHmacSHA256"
    private const val KDF_ITER = 210_000
    private const val MIN_EPOCH_MS = 0L
    private const val MAX_EPOCH_MS = 4_102_444_800_000L // 2100-01-01 UTC
    private const val MAX_ACCOUNTS = 500
    private const val MAX_TXNS = 50_000
    private const val MAX_PERSONS = 5_000
    private const val MAX_OTHER = 2_000

    private val ACCOUNT_TYPES = setOf("BANK", "CASH", "CARD", "ASSET")
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
        val iter = o.optInt("iter", KDF_ITER)
        require(iter in 100_000..1_000_000) { "Unsupported backup key derivation settings." }
        val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(o.getString("ct"), Base64.NO_WRAP)
        require(salt.size >= 16 && iv.size == 12 && ct.size >= 16) { "Corrupted encrypted backup." }
        val key = deriveKey(passphrase, salt, iter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    suspend fun toJson(db: AppDatabase): String = withContext(Dispatchers.IO) {
        val d = db.dao()
        val accs = d.allAccounts(); val txns = d.allTxns(); val budgets = d.allBudgets()
        val cats = d.allCategories(); val recs = d.allRecurring(); val persons = d.allPersons()
        JSONObject().apply {
            put("app", "Arthak"); put("schema", 2); put("exportedAt", System.currentTimeMillis())
            put("accounts", JSONArray().also { a -> accs.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("opening", it.openingBalance).put("color", it.color)) } })
            put("transactions", JSONArray().also { a -> txns.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("date", it.date).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget).put("created", it.createdAt)
                    .put("person", it.personId).put("owed", it.owed).put("from", it.routeFrom).put("toPlace", it.routeTo).put("items", it.items))
            } })
            put("persons", JSONArray().also { a -> persons.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("rel", it.relation).put("phone", it.phone).put("note", it.note)) } })
            put("budgets", JSONArray().also { a -> budgets.forEach { a.put(JSONObject().put("name", it.name).put("amount", it.amount)) } })
            put("categories", JSONArray().also { a -> cats.forEach { a.put(JSONObject().put("name", it.name).put("kind", it.kind).put("icon", it.icon)) } })
            put("recurring", JSONArray().also { a -> recs.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type).put("amount", it.amount).put("merchant", it.merchant)
                    .put("note", it.note).put("category", it.category).put("account", it.accountId)
                    .put("to", it.toAccountId ?: JSONObject.NULL).put("tag", it.tag).put("exclude", it.excludeFromBudget)
                    .put("freq", it.freq).put("nextDue", it.nextDue))
            } })
        }.toString()
    }

    /** Replaces everything with the backup's contents. Supports encrypted and legacy plaintext backups. */
    suspend fun restore(db: AppDatabase, rawJson: String, passphrase: String? = null): Int = withContext(Dispatchers.IO) {
        val json = if (isEncrypted(rawJson)) {
            decrypt(rawJson, passphrase.orEmpty())
        } else {
            rawJson
        }
        val o = JSONObject(json)
        require(o.optString("app") in listOf("Arthak", "M_Tracker")) { "Not an Arthak backup." }

        // Sanity-check and validate the whole file BEFORE touching the database.
        fun JSONObject.toOrNull(k: String): Long? = if (isNull(k)) null else getLong(k)
        fun requireFinite(v: Double, label: String): Double {
            require(v.isFinite()) { "Invalid $label in backup." }
            return v
        }

        val accArr = o.getJSONArray("accounts")
        val txnArr = o.getJSONArray("transactions")
        val personArr = o.optJSONArray("persons")
        val budgetArr = o.getJSONArray("budgets")
        val catArr = o.getJSONArray("categories")
        val recArr = o.getJSONArray("recurring")

        require(accArr.length() in 1..MAX_ACCOUNTS) { "Invalid account count in backup." }
        require(txnArr.length() <= MAX_TXNS) { "Backup exceeds maximum transaction count." }
        require((personArr?.length() ?: 0) <= MAX_PERSONS) { "Backup exceeds maximum people count." }
        require(budgetArr.length() <= MAX_OTHER && catArr.length() <= MAX_OTHER && recArr.length() <= MAX_OTHER) {
            "Backup exceeds allowed metadata limits."
        }

        val accs = (0 until accArr.length()).map { accArr.getJSONObject(it) }.map {
            val type = it.getString("type").trim().uppercase()
            require(type in ACCOUNT_TYPES) { "Invalid account type." }
            val name = it.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 100) { "Invalid account name." }
            Account(it.getLong("id"), name, type, requireFinite(it.getDouble("opening"), "openingBalance"), it.optInt("color", 0))
        }
        val accountIds = accs.map { it.id }.toSet()

        val persons = personArr?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                val name = it.getString("name").trim()
                require(name.isNotEmpty() && name.length <= 100) { "Invalid person name." }
                Person(
                    it.getLong("id"), name,
                    it.optString("rel", "").take(50),
                    it.optString("phone", "").take(24),
                    it.optString("note", "").take(300)
                )
            }
        } ?: emptyList()

        val txns = (0 until txnArr.length()).map { txnArr.getJSONObject(it) }.map {
            val type = it.getString("type").trim().uppercase()
            require(type in TXN_TYPES) { "Invalid transaction type." }
            val amt = requireFinite(it.getDouble("amount"), "amount")
            require(amt >= 0.0) { "Negative transaction amount." }
            val owed = requireFinite(it.optDouble("owed", 0.0), "owed")
            val date = it.getLong("date")
            require(date in MIN_EPOCH_MS..MAX_EPOCH_MS) { "Transaction date out of valid range." }
            val accId = it.getLong("account")
            require(accId in accountIds || (type == "PERSON_ADJ" && accId == 0L)) { "Transaction references unknown account." }
            val toId = it.toOrNull("to")
            if (toId != null) require(toId in accountIds) { "Transfer references unknown destination account." }
            Txn(
                it.getLong("id"), type, amt,
                it.getString("merchant").take(120), it.getString("note").take(500), date,
                it.getString("category").take(80), accId, toId,
                it.getString("tag").take(60), it.getBoolean("exclude"), it.optLong("created", 0).coerceIn(0L, MAX_EPOCH_MS),
                it.optLong("person", 0), owed,
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
            require(nextDue in 946_684_800_000L..MAX_EPOCH_MS) { "Recurring nextDue out of valid range." } // >= 2000-01-01
            Recurring(
                it.getLong("id"), type, amt,
                it.getString("merchant").take(120), it.getString("note").take(500),
                it.getString("category").take(80), accId, toId,
                it.getString("tag").take(60), it.getBoolean("exclude"),
                freq, nextDue
            )
        }

        val d = db.dao()
        db.withTransaction {
            d.clearTxns(); d.clearPersons(); d.clearRecurring(); d.clearAccounts(); d.clearBudgets(); d.clearCategories()
            accs.forEach { d.insertAccount(it) }
            txns.forEach { d.insert(it) }
            budgets.forEach { d.upsertBudget(it) }
            cats.forEach { d.insertCategory(it) }
            recs.forEach { d.insertRecurring(it) }
            persons.forEach { d.insertPerson(it) }
        }
        txns.size
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
            val name = "arthak_backup_${LocalDate.now()}.json"
            dir.findFile(name)?.delete()
            val f = dir.createFile("application/json", name) ?: return@withContext BackupStatus.FAILED
            c.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(encryptedJson.toByteArray(Charsets.UTF_8)) }
            dir.listFiles().filter { (it.name?.startsWith("arthak_backup_") == true || it.name?.startsWith("m_tracker_backup_") == true) }
                .sortedByDescending { it.name }.drop(8).forEach { it.delete() }
            p.setLastBackup(System.currentTimeMillis())
            BackupStatus.OK
        } catch (e: Exception) { BackupStatus.FAILED }
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
        if (p.autoBackup.value && p.backupUri.value.isNotBlank() && p.hasBackupPassphrase.value) {
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build())
        } else wm.cancelUniqueWork(NAME)
    }
}
