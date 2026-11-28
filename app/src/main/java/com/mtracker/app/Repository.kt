package com.mtracker.app

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

class Repository(private val db: AppDatabase) {
    private val d = db.dao()
    val txns = d.transactions()
    val accounts = d.accounts()
    val budgets = d.budgets()
    val pending = d.pending()
    val recurring = d.recurring()
    val categories = d.categories()
    val dayMarks = d.dayMarks()
    val persons = d.persons()
    val deletedTxns = d.deletedTxns()
    val changeHistory = d.changeHistory()
    val groups = d.groups()
    val groupMembers = d.allGroupMembersFlow()
    val groupDebts = d.groupDebts()
    val trips = d.trips()
    val tripMembers = d.tripMembers()
    val tripDebts = d.tripDebts()

    private fun Txn.asJson() = JSONObject()
        .put("id", id).put("type", type).put("amount", amount).put("merchant", merchant)
        .put("note", note).put("date", date).put("category", category).put("account", accountId)
        .put("to", toAccountId ?: JSONObject.NULL).put("tag", tag).put("exclude", excludeFromBudget)
        .put("created", createdAt).put("person", personId).put("owed", owed)
        .put("from", routeFrom).put("toPlace", routeTo).put("items", items).put("trip", tripId)

    private fun Txn.asDeleted() = DeletedTxn(
        id = id, type = type, amount = amount, merchant = merchant, note = note, date = date,
        category = category, accountId = accountId, toAccountId = toAccountId, tag = tag,
        excludeFromBudget = excludeFromBudget, createdAt = createdAt, personId = personId,
        owed = owed, routeFrom = routeFrom, routeTo = routeTo, items = items, tripId = tripId,
        deletedAt = System.currentTimeMillis()
    )

    private fun DeletedTxn.asTxn() = Txn(
        id = id, type = type, amount = amount, merchant = merchant, note = note, date = date,
        category = category, accountId = accountId, toAccountId = toAccountId, tag = tag,
        excludeFromBudget = excludeFromBudget, createdAt = createdAt, personId = personId,
        owed = owed, routeFrom = routeFrom, routeTo = routeTo, items = items, tripId = tripId
    )

    private suspend fun record(entity: String, id: Long, action: String, payload: String) {
        d.insertChange(ChangeHistory(entity = entity, entityId = id, action = action, payload = payload, changedAt = System.currentTimeMillis()))
    }

    /** Restoring a recycled txn must never leave it pointing at a missing or hidden account. */
    private suspend fun ensureAccount(id: Long) {
        if (id == 0L) return
        if (d.allAccounts().none { it.id == id }) {
            d.insertAccount(Account(id = id, name = "Restored account", type = "BANK"))
        } else {
            d.unarchiveAccount(id)
        }
    }

    /** A recycled row restored after its person was removed must not create a balance no tile can settle. */
    private suspend fun Txn.withoutMissingPerson(): Txn =
        if (personId != 0L && d.personById(personId) == null) copy(personId = 0L) else this

    /** Only split-generated expense rows are baked into Trip.spent; keep that counter in sync. */
    private suspend fun adjustTripSpent(t: Txn, delta: Double) {
        if (t.tripId == 0L || t.type != "EXPENSE" || t.tag != "trip") return
        val trip = d.tripById(t.tripId) ?: return
        val next = round2(trip.spent + delta)
        if (next >= 0.0 && next <= 1_000_000_000.0) d.updateTrip(trip.copy(spent = next))
    }

    suspend fun save(t: Txn) = db.withTransaction {
        if (t.id == 0L) {
            val row = if (t.createdAt == 0L) t.copy(createdAt = System.currentTimeMillis()) else t
            d.insert(row)
            adjustTripSpent(row, row.amount)
        } else {
            val old = d.txnById(t.id)
            if (old != null && old != t) record("TXN", t.id, "EDIT", old.asJson().toString())
            val keepCreated = if (t.createdAt == 0L) old?.createdAt ?: System.currentTimeMillis() else t.createdAt
            val row = t.copy(createdAt = keepCreated)
            if (old != null) adjustTripSpent(old, -old.amount)
            d.update(row)
            adjustTripSpent(row, row.amount)
        }
    }

    /** Undo after a swipe-delete: put the row back and clear its Recycle Bin entry. */
    suspend fun restore(t: Txn) = db.withTransaction {
        val row = t.withoutMissingPerson()
        ensureAccount(row.accountId)
        row.toAccountId?.let { ensureAccount(it) }
        d.deleteDeletedTxn(t.id)
        d.insert(row)
        adjustTripSpent(row, row.amount)
    }

    suspend fun delete(t: Txn) = db.withTransaction {
        d.insertDeletedTxn(t.asDeleted())
        record("TXN", t.id, "DELETE", t.asJson().toString())
        d.delete(t)
        adjustTripSpent(t, -t.amount)
    }

    suspend fun restoreDeleted(item: DeletedTxn) = db.withTransaction {
        val t = item.asTxn().withoutMissingPerson()
        ensureAccount(t.accountId)
        t.toAccountId?.let { ensureAccount(it) }
        d.insert(t)
        d.deleteDeletedTxn(item.id)
        record("TXN", item.id, "RESTORE", t.asJson().toString())
        adjustTripSpent(t, t.amount)
    }

    suspend fun deleteDeletedForever(id: Long) {
        d.deleteDeletedTxn(id)
        record("TXN", id, "DELETE_FOREVER", "")
    }

    suspend fun seedDefaultHeads() {
        DEFAULT_EXPENSE_CATEGORIES.forEachIndexed { i, it ->
            d.insertCategory(Category(it, "EXPENSE", defaultHeadIcon(it, "EXPENSE"), i))
        }
        DEFAULT_INCOME_CATEGORIES.forEachIndexed { i, it ->
            d.insertCategory(Category(it, "INCOME", defaultHeadIcon(it, "INCOME"), i))
        }
    }

    suspend fun saveAccount(a: Account) = db.withTransaction {
        if (a.isDefault) d.clearDefaultAccount()
        if (a.id == 0L) d.insertAccount(a) else d.updateAccount(a)
    }

    suspend fun deleteAccount(id: Long) = db.withTransaction {
        d.allTxns().filter { (it.accountId == id || it.toAccountId == id) && !PeopleMath.isSettlement(it) }.forEach { t ->
            d.insertDeletedTxn(t.asDeleted())
            record("TXN", t.id, "DELETE", t.asJson().toString())
            adjustTripSpent(t, -t.amount)
        }
        d.deleteTxnsOfAccount(id)
        d.deleteRecurringOfAccount(id)
        d.deletePendingOfAccount(id)
        // Keep the account row so recycled transactions can still point at it.
        d.archiveAccount(id)
        record("ACCOUNT", id, "ARCHIVE", JSONObject().put("id", id).toString())
    }

    suspend fun setBudget(name: String, amount: Double) {
        if (amount <= 0) d.deleteBudget(name) else d.upsertBudget(Budget(name, amount))
    }

    suspend fun saveWithRepeat(t: Txn, repeat: String, pendingId: Long) = db.withTransaction {
        save(t)
        if (t.id == 0L && repeat != "NEVER") d.insertRecurring(
            Recurring(
                type = t.type, amount = t.amount, merchant = t.merchant, note = t.note, category = t.category,
                accountId = t.accountId, toAccountId = t.toAccountId, tag = t.tag,
                excludeFromBudget = t.excludeFromBudget, freq = repeat, nextDue = nextDate(t.date, repeat)
            )
        )
        if (pendingId != 0L) d.deletePending(pendingId)
    }

    /** Creates every repeating transaction that has come due (catches up if the app wasn't opened). */
    suspend fun materializeDue(): Int = db.withTransaction {
        val td = today()
        var n = 0
        for (r in d.allRecurring()) {
            var due = r.nextDue
            var guard = 0
            while (due <= td && guard++ < 400) {
                d.insert(
                    Txn(
                        type = r.type, amount = r.amount, merchant = r.merchant, note = r.note, date = due,
                        category = r.category, accountId = r.accountId, toAccountId = r.toAccountId,
                        tag = r.tag, excludeFromBudget = r.excludeFromBudget,
                        createdAt = System.currentTimeMillis()
                    )
                )
                n++
                due = nextDate(due, r.freq)
            }
            if (due != r.nextDue) d.updateRecurring(r.copy(nextDue = due))
        }
        n
    }

    suspend fun deleteRecurring(id: Long) = d.deleteRecurring(id)
    suspend fun deletePending(id: Long) = d.deletePending(id)
    suspend fun addCategory(name: String, kind: String, icon: String = "") = db.withTransaction {
        val clean = HeadsMath.cleanName(name)
        require(clean.isNotBlank()) { "Name is required." }
        require(kind in setOf("EXPENSE", "INCOME")) { "Invalid head type." }
        require(icon.isEmpty() || icon in HEAD_ICON_MAP) { "Unknown icon." }
        require(d.categoryByName(clean, kind) == null) { "A head with that name already exists." }
        val next = d.allCategories().filter { it.kind == kind }.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        d.insertCategory(Category(clean, kind, icon, next))
    }

    suspend fun deleteCategory(name: String, kind: String) = d.deleteCategory(name, kind)

    /** Hidden heads stay in old transactions but leave pickers and the quick strip. */
    suspend fun setCategoryHidden(name: String, kind: String, hidden: Boolean) = db.withTransaction {
        require(d.categoryByName(name, kind) != null) { "Head not found." }
        d.setCategoryHidden(name, kind, hidden)
    }

    suspend fun setCategoryIcon(name: String, kind: String, icon: String) = db.withTransaction {
        require(d.categoryByName(name, kind) != null) { "Head not found." }
        require(icon.isEmpty() || icon in HEAD_ICON_MAP) { "Unknown icon." }
        d.setCategoryIcon(name, kind, icon)
    }

    /** Order is per kind. A stale or partial list is rejected instead of silently reordering. */
    suspend fun reorderCategories(kind: String, names: List<String>) = db.withTransaction {
        require(kind in setOf("EXPENSE", "INCOME")) { "Invalid head type." }
        val existing = d.allCategories().filter { it.kind == kind }.map { it.name }.toSet()
        require(names.size == existing.size && names.toSet() == existing) { "Head list is out of date." }
        names.forEachIndexed { i, n -> d.setCategoryOrder(n, kind, i) }
    }

    /** Renaming keeps past transactions, recycled rows, repeats and budgets pointing at the new name. */
    suspend fun renameCategory(name: String, kind: String, raw: String) = db.withTransaction {
        val clean = HeadsMath.cleanName(raw)
        require(clean.isNotBlank()) { "Name is required." }
        require(clean != name) { "Choose a different name." }
        require(d.categoryByName(name, kind) != null) { "Head not found." }
        require(d.categoryByName(clean, kind) == null) { "A head with that name already exists." }
        val budgets = d.allBudgets().filter { it.name == name || it.name == DAY_PREFIX + name }
        budgets.forEach { b ->
            val next = if (b.name == name) clean else DAY_PREFIX + clean
            require(d.allBudgets().none { it.name == next }) { "A budget with that name already exists." }
        }
        d.renameCategoryRow(name, kind, clean)
        d.renameTxnCategory(name, kind, clean)
        d.renameDeletedTxnCategory(name, kind, clean)
        d.renameRecurringCategory(name, kind, clean)
        budgets.forEach { b ->
            d.deleteBudget(b.name)
            d.upsertBudget(b.copy(name = if (b.name == name) clean else DAY_PREFIX + clean))
        }
        record("HEAD", 0L, "RENAME", JSONObject().put("kind", kind).put("from", name).put("to", clean).toString())
    }
    suspend fun markNoSpend(day: Long) = d.insertMark(DayMark(day))
    suspend fun addPerson(name: String): Long = db.withTransaction {
        val clean = PeopleMath.name(name)
        require(clean.isNotBlank()) { "Name is required." }
        val id = d.insertPerson(Person(name = clean))
        record("PERSON", id, "CREATE", JSONObject().put("name", clean).toString())
        id
    }
    suspend fun personById(id: Long): Person? = d.personById(id)

    suspend fun deletePerson(id: Long) = db.withTransaction {
        d.personById(id)?.let { p ->
            record("PERSON", id, "DELETE", JSONObject().put("id", p.id).put("name", p.name).toString())
        }
        d.allTxns().filter { it.personId == id }.forEach { t ->
            record("TXN", t.id, "PERSON_DETACH", t.asJson().toString())
        }
        d.detachPerson(id)
        d.detachDeletedPerson(id)
        d.deleteGroupMembersOfPerson(id); d.deleteGroupDebtsOfPerson(id)
        d.deleteTripMembersOfPerson(id); d.deleteTripDebtsOfPerson(id)
        d.deletePerson(id)
    }
    suspend fun addTxn(t: Txn) { save(t) }
    suspend fun savePerson(p: Person): Long = db.withTransaction {
        val clean = PeopleMath.name(p.name)
        require(clean.isNotBlank()) { "Name is required." }
        val row = p.copy(
            name = clean, relation = p.relation.trim().take(50),
            phone = p.phone.trim().take(24), note = p.note.trim().take(300)
        )
        if (p.id == 0L) {
            val id = d.insertPerson(row)
            record("PERSON", id, "CREATE", JSONObject().put("name", clean).toString())
            id
        } else {
            d.updatePerson(row)
            record("PERSON", p.id, "EDIT", JSONObject().put("name", clean).toString())
            p.id
        }
    }

    /** Splits whole rupees into paise so the saved shares always add back to the exact bill. */
    private fun splitCents(totalCents: Long, parts: List<Pair<Long, Double>>): Map<Long, Long> {
        val sum = parts.sumOf { it.second }
        require(sum > 0.0 && sum.isFinite()) { "Invalid split weights." }
        require(parts.all { it.second >= 0.0 && it.second.isFinite() }) { "Split weights must be positive." }
        val raw = parts.map { totalCents * it.second / sum }
        val cents = raw.map { floor(it).toLong() }.toMutableList()
        var left = totalCents - cents.sum()
        val order = raw.indices.sortedByDescending { raw[it] - cents[it] }
        var i = 0
        while (left > 0 && order.isNotEmpty()) {
            cents[order[i % order.size]]++
            left--
            i++
        }
        return parts.mapIndexed { idx, part -> part.first to cents[idx] }.toMap()
    }

    /**
     * Saves an occasion split. paidBy = 0 means you paid; otherwise it is the person who paid.
     * You are always a participant. The group card records every person's debt to the payer;
     * the normal ledger only records debts involving you, so a person page stays uncluttered.
     */
    suspend fun saveGroup(
        name: String, note: String, members: List<Long>, total: Double,
        mode: String, weights: Map<Long, Double>, paidBy: Long, accountId: Long, date: Long
    ): Long = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Group name is required." }
        require(total > 0.0 && total.isFinite() && total <= 1_000_000_000.0) { "Invalid group total." }
        require(members.isNotEmpty()) { "Choose at least one person." }
        require(members.size == members.toSet().size) { "A person is selected twice." }
        val known = d.allPersons().map { it.id }.toSet()
        require(members.all { it in known }) { "Group references a missing person." }
        require(paidBy == 0L || paidBy in members) { "Payer must be one of the selected people." }
        if (paidBy == 0L) require(accountId != 0L) { "Choose an account for your payment." }
        require(date in 0L..4_102_444_800_000L) { "Group date out of range." }

        val peopleOrder = d.allPersons().map { it.id }
        val participants = listOf(0L) + peopleOrder.filter { it in members.toSet() }
        val totalCents = Math.round(total * 100.0)
        val shares = splitCents(totalCents, participants.map { it to (weights[it] ?: 1.0) })
        val gid = d.insertGroup(OccasionGroup(name = trimmed.take(80), note = note.trim().take(300), createdAt = System.currentTimeMillis()))
        members.forEach { d.insertGroupMember(GroupMember(gid, it)) }
        // Everyone except the payer owes the payer their own share. This keeps the whole
        // group visible inside the group card while the person page still shows only your own dealings.
        participants.filter { it != paidBy }.forEach { p ->
            val cents = shares[p] ?: 0L
            if (cents > 0L) d.insertGroupDebt(GroupDebt(groupId = gid, fromPersonId = p, toPersonId = paidBy, amount = cents / 100.0))
        }
        val noteText = "Group · ${trimmed.take(60)}"
        val now = System.currentTimeMillis()
        for (p in participants) {
            val cents = shares[p] ?: 0L
            if (cents <= 0L) continue
            val share = cents / 100.0
            val t = when {
                paidBy == 0L && p == 0L -> Txn(type = "EXPENSE", amount = share, note = noteText, date = date,
                    category = "People", accountId = accountId, createdAt = now)
                paidBy == 0L -> Txn(type = "EXPENSE", amount = share, note = noteText, date = date,
                    category = "People", accountId = accountId, personId = p, owed = share, createdAt = now)
                p == 0L -> Txn(type = "PERSON_ADJ", amount = share, note = noteText, date = date,
                    category = "People", accountId = 0, personId = paidBy, owed = -share, createdAt = now)
                else -> continue
            }
            d.insert(t)
        }
        record("GROUP", gid, "CREATE", JSONObject().put("name", trimmed).put("total", total)
            .put("mode", mode).put("paidBy", paidBy).toString())
        gid
    }

    suspend fun deleteGroup(id: Long) = db.withTransaction {
        record("GROUP", id, "DELETE", "")
        d.deleteGroupMembers(id)
        d.deleteGroupDebts(id)
        d.deleteGroup(id)
    }

    /** Settles one group debt that involves you, using the existing person-balance ledger. */
    suspend fun settleGroupDebt(debt: GroupDebt, accountId: Long) = db.withTransaction {
        require(debt.fromPersonId == 0L || debt.toPersonId == 0L) { "This debt is between two other people." }
        require(accountId != 0L) { "Choose an account." }
        // Conditional update is the guard: two rapid taps cannot write two settlement rows.
        require(d.settleGroupDebt(debt.id) == 1) { "Debt is already settled." }
        val other = if (debt.fromPersonId == 0L) debt.toPersonId else debt.fromPersonId
        val t = if (debt.fromPersonId == 0L) {
            Txn(type = "PERSON_OUT", amount = debt.amount, note = "Group · settled", date = today(),
                category = "People", accountId = accountId, personId = other, owed = debt.amount,
                createdAt = System.currentTimeMillis())
        } else {
            Txn(type = "PERSON_IN", amount = debt.amount, note = "Group · settled", date = today(),
                category = "People", accountId = accountId, personId = other, owed = -debt.amount,
                createdAt = System.currentTimeMillis())
        }
        d.insert(t)
        record("GROUP_DEBT", debt.id, "SETTLE", JSONObject().put("amount", debt.amount).toString())
    }

    /** Saves a person settlement and closes matching open group debts that it fully covers. */
    suspend fun settlePerson(t: Txn) = db.withTransaction {
        require(t.personId != 0L) { "Choose a person to settle." }
        d.insert(t)
        val youPay = t.type == "PERSON_OUT"
        val theyPay = t.type == "PERSON_IN"
        if (!youPay && !theyPay) return@withTransaction
        var left = t.amount
        for (debt in d.openGroupDebtsWith(t.personId)) {
            if (left <= 0.005) break
            val directionMatches = if (youPay) debt.fromPersonId == 0L else debt.fromPersonId == t.personId
            if (!directionMatches || debt.amount > left + 0.005) continue
            if (d.settleGroupDebt(debt.id) == 1) {
                left -= debt.amount
                record("GROUP_DEBT", debt.id, "SETTLE", JSONObject().put("amount", debt.amount).put("via", t.type).toString())
            }
        }
        for (debt in d.openTripDebtsWith(t.personId)) {
            if (left <= 0.005) break
            val directionMatches = if (youPay) debt.fromPersonId == 0L else debt.fromPersonId == t.personId
            if (!directionMatches) continue
            val portion = minOf(debt.amount, left)
            if (portion <= 0.005) continue
            if (portion >= debt.amount - 0.005) {
                if (d.settleTripDebt(debt.id, debt.note) == 1) {
                    left -= debt.amount
                    record("TRIP_DEBT", debt.id, "SETTLE", JSONObject().put("amount", debt.amount).put("via", t.type).toString())
                }
            } else {
                val remaining = round2(debt.amount - portion)
                if (remaining > 0.005 && d.partiallyPayTripDebt(debt.id, debt.amount, remaining) == 1) {
                    left = round2(left - portion)
                    record("TRIP_DEBT", debt.id, "PARTIAL", JSONObject().put("paid", portion)
                        .put("remaining", remaining).put("via", t.type).toString())
                }
            }
        }
    }

    // ---------- trips ----------

    /** Creates or edits a trip. You are always a member; archived trips keep their history. */
    suspend fun saveTrip(
        id: Long, name: String, place: String, startDate: Long, endDate: Long,
        budget: Double, note: String, members: List<Long>
    ): Long = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Trip name is required." }
        require(startDate in 0L..4_102_444_800_000L && endDate in startDate..4_102_444_800_000L) { "Invalid trip dates." }
        require(budget >= 0.0 && budget.isFinite() && budget <= 1_000_000_000.0) { "Invalid trip budget." }
        val people = members.filter { it != 0L }.distinct()
        val known = d.allPersons().map { it.id }.toSet()
        require(people.all { it in known }) { "Trip references a missing person." }
        val now = System.currentTimeMillis()
        val cleanPlace = place.trim().take(80)
        val cleanNote = note.trim().take(300)
        val cleanName = trimmed.take(80)
        val tid = if (id == 0L) {
            d.insertTrip(Trip(name = cleanName, place = cleanPlace, startDate = startDate, endDate = endDate,
                budget = round2(budget), note = cleanNote, createdAt = now))
        } else {
            val old = d.tripById(id) ?: throw IllegalArgumentException("Trip not found.")
            d.updateTrip(old.copy(name = cleanName, place = cleanPlace, startDate = startDate,
                endDate = endDate, budget = round2(budget), note = cleanNote))
            id
        }
        // Nobody with an unpaid or settled debt can be dropped from the trip: that would
        // leave a debt pointing at a non-member and make later backups fail validation.
        val owedPeople = if (id == 0L) emptySet() else d.allTripDebts().filter { it.tripId == tid }
            .flatMap { listOf(it.fromPersonId, it.toPersonId) }.filter { it != 0L }.toSet()
        d.deleteTripMembers(tid)
        (listOf(0L) + people + owedPeople).distinct().forEach { d.insertTripMember(TripMember(tid, it)) }
        record("TRIP", tid, if (id == 0L) "CREATE" else "EDIT",
            JSONObject().put("name", cleanName).put("people", people.size).toString())
        tid
    }

    /** Archiving hides a trip without touching its expenses or debts. */
    suspend fun setTripArchived(id: Long, archived: Boolean) = db.withTransaction {
        d.setTripArchived(id, archived)
        record("TRIP", id, if (archived) "ARCHIVE" else "RESTORE", "")
    }

    /** Permanent removal: trip metadata, members and debts go; ledger rows stay, only their trip link clears. */
    suspend fun deleteTrip(id: Long) = db.withTransaction {
        record("TRIP", id, "DELETE", "")
        d.deleteTripDebts(id)
        d.deleteTripMembers(id)
        d.clearTxnTripLink(id)
        d.clearDeletedTxnTripLink(id)
        d.deleteTrip(id)
    }

    /**
     * Records something paid on the trip. payer 0 means you paid from your account; otherwise
     * a member paid. Every share becomes a trip debt between two members so the trip shows all
     * of them, while the normal ledger only gets rows for debts involving you.
     */
    suspend fun addTripExpense(
        tripId: Long, title: String, amount: Double, payer: Long, participants: List<Long>,
        weights: Map<Long, Double>, accountId: Long, date: Long
    ) = db.withTransaction {
        val trip = d.tripById(tripId) ?: throw IllegalArgumentException("Trip not found.")
        require(!trip.archived) { "Trip is archived." }
        val cleanTitle = title.trim().ifBlank { "Trip expense" }
        require(amount > 0.0 && amount.isFinite() && amount <= 1_000_000_000.0) { "Invalid amount." }
        require(date in 0L..4_102_444_800_000L) { "Expense date out of range." }
        val members = d.allTripMembers().filter { it.tripId == tripId }.map { it.personId }.toSet()
        val sharesOf = participants.distinct()
        require(sharesOf.isNotEmpty() && payer in sharesOf) { "Payer must be one of the people sharing this." }
        require(sharesOf.all { it in members }) { "Choose people from this trip." }
        require(weights.values.all { it >= 0.0 && it.isFinite() } && weights.values.any { it > 0.0 }) { "Invalid split weights." }
        if (payer == 0L) require(accountId != 0L) { "Choose an account for your payment." }
        val cents = splitCents(Math.round(amount * 100.0), sharesOf.map { it to (weights[it] ?: 1.0) })
        val now = System.currentTimeMillis()
        val label = cleanTitle.take(60)
        val note = "Trip · $label"
        sharesOf.filter { it != payer }.forEach { p ->
            val share = cents[p] ?: 0L
            if (share > 0L) d.insertTripDebt(TripDebt(tripId = tripId, fromPersonId = p, toPersonId = payer,
                amount = share / 100.0, note = label, createdAt = now))
        }
        if (payer == 0L) {
            val mine = cents[0L] ?: 0L
            if (mine > 0L) d.insert(Txn(type = "EXPENSE", amount = mine / 100.0, note = note, date = date,
                category = "Travel", accountId = accountId, tag = "trip", tripId = tripId, createdAt = now))
            sharesOf.filter { it != 0L }.forEach { p ->
                val share = cents[p] ?: 0L
                if (share > 0L) d.insert(Txn(type = "EXPENSE", amount = share / 100.0, note = note, date = date,
                    category = "Travel", accountId = accountId, personId = p, owed = share / 100.0,
                    tripId = tripId, createdAt = now))
            }
        } else {
            val mine = cents[0L] ?: 0L
            if (mine > 0L) d.insert(Txn(type = "PERSON_ADJ", amount = mine / 100.0, note = note, date = date,
                category = "People", accountId = 0, personId = payer, owed = -(mine / 100.0), tag = "trip",
                tripId = tripId, createdAt = now))
        }
        val updatedSpent = round2(trip.spent + amount)
        require(updatedSpent <= 1_000_000_000.0) { "Trip total is too large." }
        d.updateTrip(trip.copy(spent = updatedSpent))
        record("TRIP", tripId, "EXPENSE", JSONObject().put("title", label).put("amount", amount)
            .put("payer", payer).toString())
    }

    /**
     * Settles one trip debt. Debts involving you move real money through an account; debts
     * between two other members are settled between them and only change the trip record.
     */
    suspend fun settleTripDebt(debt: TripDebt, accountId: Long, note: String) = db.withTransaction {
        val message = note.trim().take(200)
        val finalNote = if (message.isBlank()) debt.note else "${debt.note} · $message".take(200)
        require(d.settleTripDebt(debt.id, finalNote) == 1) { "Debt is already settled." }
        if (debt.fromPersonId == 0L || debt.toPersonId == 0L) {
            require(accountId != 0L) { "Choose an account." }
            val other = if (debt.fromPersonId == 0L) debt.toPersonId else debt.fromPersonId
            val t = if (debt.fromPersonId == 0L) {
                Txn(type = "PERSON_OUT", amount = debt.amount, note = message.ifBlank { "Trip · settled" },
                    date = today(), category = "People", accountId = accountId, personId = other,
                    owed = debt.amount, tripId = debt.tripId, createdAt = System.currentTimeMillis())
            } else {
                Txn(type = "PERSON_IN", amount = debt.amount, note = message.ifBlank { "Trip · settled" },
                    date = today(), category = "People", accountId = accountId, personId = other,
                    owed = -debt.amount, tripId = debt.tripId, createdAt = System.currentTimeMillis())
            }
            d.insert(t)
        }
        val via = if (debt.fromPersonId != 0L && debt.toPersonId != 0L) "members" else message.ifBlank { "account" }
        record("TRIP_DEBT", debt.id, "SETTLE", JSONObject().put("amount", debt.amount)
            .put("via", via).toString())
    }

    // ---------- CSV (Storage Access Framework URIs; no storage permission) ----------
    private val header = "date,type,amount,merchant,note,category,account,to_account,tag,exclude_from_budget"
    private val zone get() = ZoneId.systemDefault()
    private val formulaTriggers = setOf('=', '+', '-', '@', '\t', '\r')

    private fun safeTextCell(s: String): String {
        // Skip leading whitespace and Unicode format chars (BOM, zero-width, etc.)
        // so `=`, `+`, `-`, `@`, tab or CR can't hide behind them in spreadsheets.
        val first = s.firstOrNull { ch -> Character.getType(ch) != Character.FORMAT.toInt() && !ch.isWhitespace() }
        val guarded = if (first != null && first in formulaTriggers) "'$s" else s
        return q(guarded)
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    suspend fun exportCsv(cr: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val accs = d.allAccounts().associateBy { it.id }
        val list = d.allTxns().sortedWith(compareBy({ it.date }, { it.id }))
        cr.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
            w.write(header + "\n")
            for (t in list) {
                val dateStr = java.time.Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate().toString()
                val amountStr = if (t.amount.isFinite()) t.amount.toString() else "0.0"
                val line = listOf(
                    q(dateStr),
                    q(t.type),
                    q(amountStr),
                    safeTextCell(t.merchant),
                    safeTextCell(t.note),
                    safeTextCell(t.category),
                    safeTextCell(accs[t.accountId]?.name ?: ""),
                    safeTextCell(t.toAccountId?.let { accs[it]?.name } ?: ""),
                    safeTextCell(t.tag),
                    q(t.excludeFromBudget.toString())
                ).joinToString(",")
                w.write(line + "\n")
            }
        }
        list.size
    }

    suspend fun importCsv(cr: ContentResolver, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = cr.openInputStream(uri)!!.use { readTextBounded(it) }.removePrefix("\uFEFF")
        val rows = parseCsv(text).drop(1).filter { it.size >= 10 }.take(50_000)
        val accs = d.allAccounts().associateBy { it.name.lowercase() }.toMutableMap()
        var n = 0
        db.withTransaction {
            suspend fun accId(name: String): Long {
                val clean = name.trim().take(100)
                val key = clean.lowercase()
                accs[key]?.let { return it.id }
                val id = d.insertAccount(Account(name = clean, type = "BANK"))
                accs[key] = Account(id, clean, "BANK")
                return id
            }
            for (r in rows) {
                val amt = r[2].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
                val date = try { LocalDate.parse(r[0].trim()).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e: Exception) { continue }
                val type = r[1].trim().uppercase()
                if (type !in listOf("EXPENSE", "INCOME", "TRANSFER") || r[6].isBlank()) continue
                d.insert(
                    Txn(
                        type = type, amount = amt,
                        merchant = r[3].take(120), note = r[4].take(500),
                        category = r[5].ifBlank { "Other" }.take(80), date = date,
                        accountId = accId(r[6]),
                        toAccountId = if (r[7].isNotBlank()) accId(r[7]) else null,
                        tag = r[8].take(60), excludeFromBudget = r[9].trim().equals("true", true)
                    )
                )
                if (type != "TRANSFER") d.insertCategory(Category(r[5].ifBlank { "Other" }.take(80), if (type == "INCOME") "INCOME" else "EXPENSE"))
                n++
            }
        }
        n
    }
}

fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val sb = StringBuilder()
    var inQ = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inQ) {
            if (ch == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
            } else sb.append(ch)
        } else {
            when (ch) {
                '"' -> inQ = true
                ',' -> { row.add(sb.toString()); sb.clear() }
                '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = mutableListOf() }
                '\r' -> {}
                else -> sb.append(ch)
            }
        }
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
    return rows
}

fun nextDate(ms: Long, freq: String): Long {
    val z = ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
    val n = when (freq) {
        "DAILY" -> d.plusDays(1)
        "WEEKLY" -> d.plusWeeks(1)
        "YEARLY" -> d.plusYears(1)
        else -> d.plusMonths(1)
    }
    return n.atStartOfDay(z).toInstant().toEpochMilli()
}
