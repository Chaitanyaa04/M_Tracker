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

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun txnById(id: Long): Txn?

    @Insert suspend fun insert(t: Txn): Long
    @Update suspend fun update(t: Txn)
    @Delete suspend fun delete(t: Txn)

    @Query("SELECT * FROM deleted_txns ORDER BY deletedAt DESC")
    fun deletedTxns(): Flow<List<DeletedTxn>>

    @Query("SELECT * FROM deleted_txns")
    suspend fun allDeletedTxns(): List<DeletedTxn>

    @Insert suspend fun insertDeletedTxn(t: DeletedTxn): Long
    @Query("DELETE FROM deleted_txns WHERE id = :id") suspend fun deleteDeletedTxn(id: Long)
    @Query("DELETE FROM deleted_txns") suspend fun clearDeletedTxns()

    @Query("SELECT * FROM change_history ORDER BY changedAt DESC")
    fun changeHistory(): Flow<List<ChangeHistory>>

    @Query("SELECT * FROM change_history")
    suspend fun allChangeHistory(): List<ChangeHistory>

    @Insert suspend fun insertChange(h: ChangeHistory)

    @Query("SELECT * FROM accounts ORDER BY id")
    fun accounts(): Flow<List<Account>>

    @Query("SELECT * FROM accounts")
    suspend fun allAccounts(): List<Account>

    @Insert suspend fun insertAccount(a: Account): Long
    @Update suspend fun updateAccount(a: Account)
    @Query("UPDATE accounts SET isDefault = 0") suspend fun clearDefaultAccount()
    @Query("UPDATE accounts SET archived = 1, isDefault = 0 WHERE id = :id") suspend fun archiveAccount(id: Long)
    @Query("UPDATE accounts SET archived = 0 WHERE id = :id") suspend fun unarchiveAccount(id: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    /** Settlement rows are audit history for already-settled group/trip debts, so archiving keeps them. */
    @Query("DELETE FROM transactions WHERE (accountId = :id OR toAccountId = :id) AND type NOT IN ('PERSON_IN', 'PERSON_OUT', 'PERSON_ADJ')")
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
    @Query("DELETE FROM pending WHERE accountId = :id") suspend fun deletePendingOfAccount(id: Long)
    @Query("SELECT * FROM pending WHERE id = :id") suspend fun pendingById(id: Long): Pending?
    @Query("SELECT * FROM pending") suspend fun allPending(): List<Pending>
    @Query("SELECT COUNT(*) FROM pending") suspend fun pendingCount(): Int
    /** Drops cached notification-derived amount/merchant from older builds; never touches ledger rows. */
    @Query("UPDATE pending SET amount = NULL, merchant = NULL WHERE amount IS NOT NULL OR merchant IS NOT NULL")
    suspend fun clearPendingExtras()

    // recurring
    @Query("SELECT * FROM recurring ORDER BY nextDue") fun recurring(): Flow<List<Recurring>>
    @Query("SELECT * FROM recurring") suspend fun allRecurring(): List<Recurring>
    @Insert suspend fun insertRecurring(r: Recurring): Long
    @Update suspend fun updateRecurring(r: Recurring)
    @Query("DELETE FROM recurring WHERE id = :id") suspend fun deleteRecurring(id: Long)
    @Query("DELETE FROM recurring WHERE accountId = :id OR toAccountId = :id") suspend fun deleteRecurringOfAccount(id: Long)

    // categories
    @Query("SELECT * FROM categories ORDER BY kind, sortOrder, name COLLATE NOCASE") fun categories(): Flow<List<Category>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCategory(c: Category)
    @Query("DELETE FROM categories WHERE name = :name AND kind = :kind") suspend fun deleteCategory(name: String, kind: String)
    @Query("SELECT * FROM categories WHERE name = :name AND kind = :kind") suspend fun categoryByName(name: String, kind: String): Category?
    @Query("UPDATE categories SET sortOrder = :pos WHERE name = :name AND kind = :kind") suspend fun setCategoryOrder(name: String, kind: String, pos: Int): Int
    @Query("UPDATE categories SET hidden = :hidden WHERE name = :name AND kind = :kind") suspend fun setCategoryHidden(name: String, kind: String, hidden: Boolean): Int
    @Query("UPDATE categories SET icon = :icon WHERE name = :name AND kind = :kind") suspend fun setCategoryIcon(name: String, kind: String, icon: String): Int
    @Query("UPDATE categories SET name = :newName WHERE name = :oldName AND kind = :kind") suspend fun renameCategoryRow(oldName: String, kind: String, newName: String): Int
    @Query("UPDATE transactions SET category = :newName WHERE category = :oldName AND type = :kind") suspend fun renameTxnCategory(oldName: String, kind: String, newName: String): Int
    @Query("UPDATE deleted_txns SET category = :newName WHERE category = :oldName AND type = :kind") suspend fun renameDeletedTxnCategory(oldName: String, kind: String, newName: String): Int
    @Query("UPDATE recurring SET category = :newName WHERE category = :oldName AND type = :kind") suspend fun renameRecurringCategory(oldName: String, kind: String, newName: String): Int

    // streak helpers
    @Query("SELECT * FROM daymarks") fun dayMarks(): Flow<List<DayMark>>
    @Query("SELECT * FROM daymarks") suspend fun allDayMarks(): List<DayMark>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMark(m: DayMark)
    @Query("SELECT COUNT(*) FROM transactions WHERE date = :d") suspend fun countOn(d: Long): Int
    @Query("SELECT COUNT(*) FROM daymarks WHERE date = :d") suspend fun markOn(d: Long): Int

    // backup / restore
    @Query("SELECT * FROM budgets") suspend fun allBudgets(): List<Budget>
    @Query("SELECT * FROM categories ORDER BY kind, sortOrder, name COLLATE NOCASE") suspend fun allCategories(): List<Category>
    @Query("DELETE FROM transactions") suspend fun clearTxns()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM budgets") suspend fun clearBudgets()
    @Query("DELETE FROM recurring") suspend fun clearRecurring()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM pending") suspend fun clearPending()
    @Query("DELETE FROM daymarks") suspend fun clearDayMarks()
    @Query("DELETE FROM deleted_txns") suspend fun clearDeletedTxnsAll()
    @Query("DELETE FROM change_history") suspend fun clearHistory()
    @Query("DELETE FROM occasion_groups") suspend fun clearGroups()

    // people
    @Query("SELECT * FROM persons ORDER BY name COLLATE NOCASE") fun persons(): Flow<List<Person>>
    @Query("SELECT * FROM persons") suspend fun allPersons(): List<Person>
    @Query("SELECT * FROM persons WHERE id = :id") suspend fun personById(id: Long): Person?
    @Insert suspend fun insertPerson(p: Person): Long
    @Query("DELETE FROM persons WHERE id = :id") suspend fun deletePerson(id: Long)
    @Query("UPDATE transactions SET personId = 0 WHERE personId = :id") suspend fun detachPerson(id: Long)
    @Query("UPDATE deleted_txns SET personId = 0 WHERE personId = :id") suspend fun detachDeletedPerson(id: Long)
    @Query("DELETE FROM persons") suspend fun clearPersons()

    @Update suspend fun updatePerson(p: Person)

    // occasion groups
    @Query("SELECT * FROM occasion_groups ORDER BY createdAt DESC") fun groups(): Flow<List<OccasionGroup>>
    @Query("SELECT * FROM occasion_groups") suspend fun allGroups(): List<OccasionGroup>
    @Insert suspend fun insertGroup(g: OccasionGroup): Long
    @Query("DELETE FROM occasion_groups WHERE id = :id") suspend fun deleteGroup(id: Long)
    @Query("SELECT * FROM group_members WHERE groupId = :groupId") suspend fun membersOf(groupId: Long): List<GroupMember>
    @Query("SELECT * FROM group_members") suspend fun allGroupMembers(): List<GroupMember>
    @Query("SELECT * FROM group_members ORDER BY groupId") fun allGroupMembersFlow(): Flow<List<GroupMember>>
    @Query("SELECT personId FROM group_members WHERE groupId = :groupId") fun groupMemberIds(groupId: Long): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGroupMember(m: GroupMember)
    @Query("DELETE FROM group_members WHERE groupId = :id") suspend fun deleteGroupMembers(id: Long)
    @Query("DELETE FROM group_members WHERE personId = :id") suspend fun deleteGroupMembersOfPerson(id: Long)
    @Query("DELETE FROM group_members") suspend fun clearGroupMembers()

    // group debts
    @Query("SELECT * FROM group_debts ORDER BY groupId, id") fun groupDebts(): Flow<List<GroupDebt>>
    @Query("SELECT * FROM group_debts") suspend fun allGroupDebts(): List<GroupDebt>
    @Insert suspend fun insertGroupDebt(d: GroupDebt): Long
    @Query("UPDATE group_debts SET settled = 1 WHERE id = :id AND settled = 0") suspend fun settleGroupDebt(id: Long): Int
    @Query("SELECT * FROM group_debts WHERE settled = 0 AND ((fromPersonId = 0 AND toPersonId = :personId) OR (fromPersonId = :personId AND toPersonId = 0)) ORDER BY groupId, id")
    suspend fun openGroupDebtsWith(personId: Long): List<GroupDebt>
    @Query("DELETE FROM group_debts WHERE groupId = :id") suspend fun deleteGroupDebts(id: Long)
    @Query("DELETE FROM group_debts WHERE fromPersonId = :id OR toPersonId = :id") suspend fun deleteGroupDebtsOfPerson(id: Long)
    @Query("DELETE FROM group_debts") suspend fun clearGroupDebts()

    // trips
    @Query("SELECT * FROM trips ORDER BY archived, startDate DESC, id DESC") fun trips(): Flow<List<Trip>>
    @Query("SELECT * FROM trips") suspend fun allTrips(): List<Trip>
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun tripById(id: Long): Trip?
    @Insert suspend fun insertTrip(t: Trip): Long
    @Update suspend fun updateTrip(t: Trip)
    @Query("UPDATE trips SET archived = :archived WHERE id = :id") suspend fun setTripArchived(id: Long, archived: Boolean)
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)
    @Query("DELETE FROM trips") suspend fun clearTrips()

    @Query("SELECT * FROM trip_members ORDER BY tripId, personId") fun tripMembers(): Flow<List<TripMember>>
    @Query("SELECT * FROM trip_members") suspend fun allTripMembers(): List<TripMember>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTripMember(m: TripMember)
    @Query("DELETE FROM trip_members WHERE tripId = :id") suspend fun deleteTripMembers(id: Long)
    @Query("DELETE FROM trip_members WHERE personId = :id") suspend fun deleteTripMembersOfPerson(id: Long)
    @Query("DELETE FROM trip_members") suspend fun clearTripMembers()

    @Query("SELECT * FROM trip_debts ORDER BY tripId, id") fun tripDebts(): Flow<List<TripDebt>>
    @Query("SELECT * FROM trip_debts") suspend fun allTripDebts(): List<TripDebt>
    @Insert suspend fun insertTripDebt(d: TripDebt): Long
    @Query("UPDATE trip_debts SET settled = 1, note = :note WHERE id = :id AND settled = 0") suspend fun settleTripDebt(id: Long, note: String): Int
    @Query("UPDATE trip_debts SET amount = :remaining WHERE id = :id AND settled = 0 AND amount = :was")
    suspend fun partiallyPayTripDebt(id: Long, was: Double, remaining: Double): Int
    @Query("SELECT * FROM trip_debts WHERE settled = 0 AND ((fromPersonId = 0 AND toPersonId = :personId) OR (fromPersonId = :personId AND toPersonId = 0)) ORDER BY tripId, id")
    suspend fun openTripDebtsWith(personId: Long): List<TripDebt>
    @Query("DELETE FROM trip_debts WHERE tripId = :id") suspend fun deleteTripDebts(id: Long)
    @Query("DELETE FROM trip_debts WHERE fromPersonId = :id OR toPersonId = :id") suspend fun deleteTripDebtsOfPerson(id: Long)
    @Query("DELETE FROM trip_debts") suspend fun clearTripDebts()

    @Query("UPDATE transactions SET tripId = :tripId WHERE id = :id") suspend fun setTxnTrip(id: Long, tripId: Long)
    @Query("UPDATE transactions SET tripId = 0 WHERE tripId = :tripId") suspend fun clearTxnTripLink(tripId: Long)
    @Query("UPDATE deleted_txns SET tripId = 0 WHERE tripId = :tripId") suspend fun clearDeletedTxnTripLink(tripId: Long)
    @Query("SELECT * FROM transactions WHERE accountId = :id AND type = 'EXPENSE' ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastExpenseFor(id: Long): Txn?
}
