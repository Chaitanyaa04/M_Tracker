package com.mtracker.app

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerMathTest {
    private val accounts = listOf(
        Account(1, "Cash", "CASH", 100.0),
        Account(2, "Bank", "BANK", 500.0)
    )

    @Test
    fun transferMovesMoneyWithoutChangingTheTotal() {
        val txn = Txn(type = "TRANSFER", amount = 50.0, date = 0L, accountId = 1, toAccountId = 2)
        val b = LedgerMath.balances(listOf(txn), accounts)
        assertEquals(50.0, b[1])
        assertEquals(550.0, b[2])
        assertEquals(600.0, b[1]!! + b[2]!!)
    }

    @Test
    fun personAdjustmentDoesNotMoveAccountMoney() {
        val txn = Txn(type = "PERSON_ADJ", amount = 75.0, date = 0L, accountId = 0, owed = -75.0)
        val b = LedgerMath.balances(listOf(txn), accounts)
        assertEquals(100.0, b[1])
        assertEquals(500.0, b[2])
    }

    @Test
    fun expenseAndIncomeUseTheSameRuleForPeopleAndOrdinarySpending() {
        val txns = listOf(
            Txn(type = "EXPENSE", amount = 20.0, date = 0L, accountId = 1),
            Txn(type = "PERSON_OUT", amount = 30.0, date = 0L, accountId = 1),
            Txn(type = "PERSON_IN", amount = 10.0, date = 0L, accountId = 2)
        )
        val b = LedgerMath.balances(txns, accounts)
        assertEquals(50.0, b[1])
        assertEquals(510.0, b[2])
    }
}
