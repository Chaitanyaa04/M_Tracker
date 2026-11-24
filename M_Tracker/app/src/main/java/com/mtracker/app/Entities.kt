package com.mtracker.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/** type: BANK | CASH | CARD. Credit cards carry a negative balance when money is owed. */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val openingBalance: Double = 0.0
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
    val excludeFromBudget: Boolean = false
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
data class Category(val name: String, val kind: String)

/** A day you confirmed "no spending", so it still counts toward your streak. */
@Entity(tableName = "daymarks")
data class DayMark(@PrimaryKey val date: Long)
