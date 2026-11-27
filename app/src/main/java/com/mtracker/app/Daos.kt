package com.mtracker.app

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    fun transactions(): Flow<List<Txn>>

    @Query("SELECT * FROM transactions")
    suspend fun allTxns(): List<Txn>

    @Insert suspend fun insert(t: Txn): Long
    @Update suspend fun update(t: Txn)
    @Delete suspend fun delete(t: Txn)

    @Query("SELECT * FROM accounts ORDER BY id")
    fun accounts(): Flow<List<Account>>

    @Query("SELECT * FROM accounts")
    suspend fun allAccounts(): List<Account>

    @Insert suspend fun insertAccount(a: Account): Long
    @Update suspend fun updateAccount(a: Account)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    @Query("DELETE FROM transactions WHERE accountId = :id OR toAccountId = :id")
    suspend fun deleteTxnsOfAccount(id: Long)

    @Query("SELECT * FROM budgets")
    fun budgets(): Flow<List<Budget>>

    @Upsert suspend fun upsertBudget(b: Budget)

    @Query("DELETE FROM budgets WHERE name = :name")
    suspend fun deleteBudget(name: String)

    // pending
    @Query("SELECT * FROM pending ORDER BY time DESC") fun pending(): Flow<List<Pending>>
    @Insert suspend fun insertPending(p: Pending): Long
    @Query("DELETE FROM pending WHERE id = :id") suspend fun deletePending(id: Long)
    @Query("SELECT * FROM pending WHERE id = :id") suspend fun pendingById(id: Long): Pending?
    @Query("SELECT COUNT(*) FROM pending") suspend fun pendingCount(): Int

    // recurring
    @Query("SELECT * FROM recurring ORDER BY nextDue") fun recurring(): Flow<List<Recurring>>
    @Query("SELECT * FROM recurring") suspend fun allRecurring(): List<Recurring>
    @Insert suspend fun insertRecurring(r: Recurring): Long
    @Update suspend fun updateRecurring(r: Recurring)
    @Query("DELETE FROM recurring WHERE id = :id") suspend fun deleteRecurring(id: Long)
    @Query("DELETE FROM recurring WHERE accountId = :id OR toAccountId = :id") suspend fun deleteRecurringOfAccount(id: Long)

    // categories
    @Query("SELECT * FROM categories ORDER BY kind, name COLLATE NOCASE") fun categories(): Flow<List<Category>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCategory(c: Category)
    @Query("DELETE FROM categories WHERE name = :name AND kind = :kind") suspend fun deleteCategory(name: String, kind: String)

    // streak helpers
    @Query("SELECT * FROM daymarks") fun dayMarks(): Flow<List<DayMark>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMark(m: DayMark)
    @Query("SELECT COUNT(*) FROM transactions WHERE date = :d") suspend fun countOn(d: Long): Int
    @Query("SELECT COUNT(*) FROM daymarks WHERE date = :d") suspend fun markOn(d: Long): Int

    // backup / restore
    @Query("SELECT * FROM budgets") suspend fun allBudgets(): List<Budget>
    @Query("SELECT * FROM categories") suspend fun allCategories(): List<Category>
    @Query("DELETE FROM transactions") suspend fun clearTxns()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM budgets") suspend fun clearBudgets()
    @Query("DELETE FROM recurring") suspend fun clearRecurring()
    @Query("DELETE FROM categories") suspend fun clearCategories()

    // people
    @Query("SELECT * FROM persons ORDER BY name COLLATE NOCASE") fun persons(): Flow<List<Person>>
    @Query("SELECT * FROM persons") suspend fun allPersons(): List<Person>
    @Insert suspend fun insertPerson(p: Person): Long
    @Query("DELETE FROM persons WHERE id = :id") suspend fun deletePerson(id: Long)
    @Query("UPDATE transactions SET personId = 0, owed = 0 WHERE personId = :id") suspend fun detachPerson(id: Long)
    @Query("DELETE FROM persons") suspend fun clearPersons()

    // de-duplication of payment prompts
    @Query("SELECT COUNT(*) FROM pending WHERE pkg = :pkg AND time > :since") suspend fun countRecent(pkg: String, since: Long): Int

    @Update suspend fun updatePerson(p: Person)
    @Query("SELECT * FROM transactions WHERE accountId = :id AND type = 'EXPENSE' ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastExpenseFor(id: Long): Txn?
}
