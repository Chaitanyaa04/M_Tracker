package com.minimal.ledger

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
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
}
