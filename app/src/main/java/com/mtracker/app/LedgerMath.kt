package com.mtracker.app

/** One balance calculation shared by the home totals and account sparklines. */
object LedgerMath {
    /** Effect of one transaction on one account. Transfers net to zero across both accounts. */
    fun delta(t: Txn, accountId: Long): Double = when (t.type) {
        "INCOME", "PERSON_IN" -> t.amount
        "EXPENSE", "PERSON_OUT" -> -t.amount
        "PERSON_ADJ" -> 0.0
        "TRANSFER" -> when {
            t.accountId == accountId -> -t.amount
            t.toAccountId == accountId -> t.amount
            else -> 0.0
        }
        else -> 0.0
    }

    fun balances(txns: List<Txn>, accounts: List<Account>): Map<Long, Double> {
        val out = accounts.associate { it.id to it.openingBalance }.toMutableMap()
        for (t in txns) {
            if (t.accountId in out) out[t.accountId] = (out[t.accountId] ?: 0.0) + delta(t, t.accountId)
            t.toAccountId?.let { to -> if (to in out) out[to] = (out[to] ?: 0.0) + delta(t, to) }
        }
        return out
    }
}
