package com.mtracker.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** type: BANK | CASH | CARD | UPI | WALLET or a custom label. Cards carry a negative balance when money is owed. */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val openingBalance: Double = 0.0,
    val color: Int = 0,
    val archived: Boolean = false,
    val isDefault: Boolean = false
)

/** type: EXPENSE | INCOME | TRANSFER. Amount is always positive. date = local start-of-day millis. */
@Entity(tableName = "transactions")
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val amount: Double,
    val merchant: String = "",
    val note: String = "",
    val date: Long,
    val category: String = "Other",
    val accountId: Long,
    val toAccountId: Long? = null,
    val tag: String = "",
    val excludeFromBudget: Boolean = false,
    val createdAt: Long = 0,
    val personId: Long = 0,
    val owed: Double = 0.0,
    val routeFrom: String = "",
    val routeTo: String = "",
    val items: String = "",
    @ColumnInfo(defaultValue = "0") val tripId: Long = 0
)

/** name = category name, or "__overall__" for the overall monthly budget. */
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey val name: String,
    val amount: Double
)

/** A payment prompt that has not been logged yet. Survives until you log or dismiss it. */
@Entity(tableName = "pending")
data class Pending(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val pkg: String,
    val label: String,
    val accountId: Long,
    val amount: Double? = null,
    val merchant: String? = null
)

/** freq: DAILY | WEEKLY | MONTHLY | YEARLY. nextDue = local start-of-day millis. */
@Entity(tableName = "recurring")
data class Recurring(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val amount: Double,
    val merchant: String = "",
    val note: String = "",
    val category: String = "Other",
    val accountId: Long,
    val toAccountId: Long? = null,
    val tag: String = "",
    val excludeFromBudget: Boolean = false,
    val freq: String,
    val nextDue: Long
)

/** kind: EXPENSE | INCOME */
@Entity(tableName = "categories", primaryKeys = ["name", "kind"])
data class Category(val name: String, val kind: String, val icon: String = "")

/** A day you confirmed "no spending", so it still counts toward your streak. */
@Entity(tableName = "daymarks")
data class DayMark(@PrimaryKey val date: Long)

/** Someone you split with, lend to, or buy things for. photoUri is only an app-private file name. */
@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relation: String = "",
    val phone: String = "",
    val note: String = "",
    @ColumnInfo(defaultValue = "''") val photoUri: String = ""
)

/** A deleted transaction kept locally so it can be recovered from the Recycle Bin. */
@Entity(tableName = "deleted_txns")
data class DeletedTxn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val amount: Double,
    val merchant: String = "",
    val note: String = "",
    val date: Long,
    val category: String = "Other",
    val accountId: Long,
    val toAccountId: Long? = null,
    val tag: String = "",
    val excludeFromBudget: Boolean = false,
    val createdAt: Long = 0,
    val personId: Long = 0,
    val owed: Double = 0.0,
    val routeFrom: String = "",
    val routeTo: String = "",
    val items: String = "",
    val deletedAt: Long,
    @ColumnInfo(defaultValue = "0") val tripId: Long = 0
)

/** A rare occasion (trip, dinner, party) whose bill is split between selected people. */
@Entity(tableName = "occasion_groups")
data class OccasionGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    val createdAt: Long
)

/** Join table: which people belong to an occasion group. */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "personId"])
data class GroupMember(val groupId: Long, val personId: Long)

/** One direction of an occasion debt. personId 0 means you; settled debts stay hidden from totals. */
@Entity(tableName = "group_debts")
data class GroupDebt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fromPersonId: Long,
    val toPersonId: Long,
    val amount: Double,
    @ColumnInfo(defaultValue = "0") val settled: Boolean = false
)

/** An offline trip with its own budget, dates and people. Archived trips stay recoverable. */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val place: String = "",
    val startDate: Long,
    val endDate: Long,
    val budget: Double = 0.0,
    val note: String = "",
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val spent: Double = 0.0
)

/** Trip participant. personId 0 means you, so you do not need a Person row. */
@Entity(tableName = "trip_members", primaryKeys = ["tripId", "personId"])
data class TripMember(val tripId: Long, val personId: Long)

/** One direction of a trip debt; settled rows stay visible in the trip audit. */
@Entity(tableName = "trip_debts")
data class TripDebt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val fromPersonId: Long,
    val toPersonId: Long,
    val amount: Double,
    @ColumnInfo(defaultValue = "0") val settled: Boolean = false,
    val note: String = "",
    val createdAt: Long = 0
)

/** Small local audit trail: what changed, when, and the previous value as JSON. */
@Entity(tableName = "change_history")
data class ChangeHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String,
    val entityId: Long,
    val action: String,
    val payload: String,
    val changedAt: Long
)

/** What you actually spent: your share only. Split / paid-for-them amounts that others owe you are excluded. */
val Txn.myAmount: Double get() = if (type == "EXPENSE") amount - owed else amount

/** Effect on the money in your accounts (transfers net to zero overall). */
fun Txn.accountDelta(): Double = when (type) {
    "INCOME", "PERSON_IN" -> amount
    "EXPENSE", "PERSON_OUT" -> -amount
    else -> 0.0
}
