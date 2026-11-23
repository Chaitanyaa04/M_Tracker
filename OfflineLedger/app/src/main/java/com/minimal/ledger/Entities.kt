package com.minimal.ledger

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
