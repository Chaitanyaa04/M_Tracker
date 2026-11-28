package com.mtracker.app

/** Pure people rules shared by the repository, view model, and JVM unit tests. */
object PeopleMath {
    /** Backup restore rejects names over 100 characters, so keep every write inside that limit. */
    fun name(raw: String): String = raw.trim().take(100)

    /** personId -> balance. Positive = they owe you, negative = you owe them. */
    fun balances(txns: List<Txn>): Map<Long, Double> =
        txns.filter { it.personId != 0L }
            .groupBy { it.personId }
            .mapValues { entry -> entry.value.sumOf { it.owed } }
}
