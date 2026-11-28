package com.mtracker.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleMathTest {
    @Test
    fun personNamesStayWithinBackupLimits() {
        assertEquals("Asha", PeopleMath.name("  Asha  "))
        assertEquals(100, PeopleMath.name("x".repeat(140)).length)
        assertTrue(PeopleMath.name("   ").isEmpty())
    }

    @Test
    fun balancesIgnoreYouAndSumBothDirections() {
        val txns = listOf(
            Txn(type = "EXPENSE", amount = 100.0, date = 0L, accountId = 1, personId = 0, owed = 40.0),
            Txn(type = "EXPENSE", amount = 100.0, date = 0L, accountId = 1, personId = 2, owed = 60.0),
            Txn(type = "PERSON_IN", amount = 20.0, date = 0L, accountId = 1, personId = 2, owed = -20.0),
            Txn(type = "PERSON_OUT", amount = 10.0, date = 0L, accountId = 1, personId = 3, owed = 10.0)
        )
        val b = PeopleMath.balances(txns)
        assertEquals(2, b.size)
        assertEquals(40.0, b[2])
        assertEquals(10.0, b[3])
    }

    @Test
    fun accountArchivalKeepsSettlementRows() {
        val ordinary = Txn(type = "EXPENSE", amount = 100.0, date = 0L, accountId = 1)
        val out = Txn(type = "PERSON_OUT", amount = 50.0, date = 0L, accountId = 1)
        val inside = Txn(type = "PERSON_IN", amount = 50.0, date = 0L, accountId = 1)
        val adj = Txn(type = "PERSON_ADJ", amount = 50.0, date = 0L, accountId = 0)
        assertTrue(!PeopleMath.isSettlement(ordinary))
        assertTrue(PeopleMath.isSettlement(out) && PeopleMath.isSettlement(inside) && PeopleMath.isSettlement(adj))
    }

    @Test
    fun detachedSplitStillCountsOnlyYourShare() {
        val t = Txn(type = "EXPENSE", amount = 100.0, date = 0L, accountId = 1, personId = 0, owed = 60.0)
        assertEquals(40.0, t.myAmount)
        assertEquals(-100.0, t.accountDelta())
    }

    @Test
    fun settlementRowsMoveMoneyAndUpdateTheBalance() {
        val lent = Txn(type = "PERSON_OUT", amount = 50.0, date = 0L, accountId = 1, personId = 2, owed = 50.0)
        val repaid = Txn(type = "PERSON_IN", amount = 50.0, date = 0L, accountId = 1, personId = 2, owed = -50.0)
        assertEquals(-50.0, lent.accountDelta())
        assertEquals(50.0, repaid.accountDelta())
        assertEquals(0.0, PeopleMath.balances(listOf(lent, repaid))[2]!!)
    }
}
