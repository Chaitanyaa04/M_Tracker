package com.mtracker.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Most common first: the quick row shows the first four until you have your own history. */
val DEFAULT_EXPENSE_CATEGORIES = listOf(
    "Food & Dining", "Groceries", "Auto & Transport", "Bills & Utilities",
    "Coffee & Snacks", "Vegetables & Fruits", "Milk & Dairy", "Gas & Fuel", "Travel",
    "Mobile Recharge", "Phone & Internet", "Electricity", "Water", "Gas Cylinder", "Rent", "EMI & Loans",
    "Shopping", "Clothing", "Household", "Laundry", "Entertainment", "Online Subscriptions",
    "Personal Care", "Health & Insurance", "Medicines", "Insurance Premium", "Fitness",
    "Education", "Books", "Stationery", "Home & Repairs", "Gifts & Donations", "Family & Kids", "Pets",
    "Investments", "Taxes & Fees", "Bank Charges", "Other"
)
val DEFAULT_INCOME_CATEGORIES = listOf(
    "Salary", "Stipend", "Pocket Money", "Freelance", "Refunds", "Cashback", "Interest", "Dividends", "Rent Received", "Gifts", "Other"
)

/** Used in v1->2, before the icon column exists. */
private fun seedCategories(db: SupportSQLiteDatabase) {
    DEFAULT_EXPENSE_CATEGORIES.forEach { db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?, 'EXPENSE')", arrayOf<Any>(it)) }
    DEFAULT_INCOME_CATEGORIES.forEach { db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?, 'INCOME')", arrayOf<Any>(it)) }
}

/** Used from v6 onward: adds heads and fills icons only where the user has not chosen one. */
private fun seedCategoriesWithIcons(db: SupportSQLiteDatabase) {
    DEFAULT_EXPENSE_CATEGORIES.forEach { name ->
        db.execSQL("INSERT OR IGNORE INTO categories(name,kind,icon) VALUES(?, 'EXPENSE', ?)", arrayOf<Any>(name, defaultHeadIcon(name, "EXPENSE")))
        db.execSQL("UPDATE categories SET icon=? WHERE name=? AND kind='EXPENSE' AND icon=''", arrayOf<Any>(defaultHeadIcon(name, "EXPENSE"), name))
    }
    DEFAULT_INCOME_CATEGORIES.forEach { name ->
        db.execSQL("INSERT OR IGNORE INTO categories(name,kind,icon) VALUES(?, 'INCOME', ?)", arrayOf<Any>(name, defaultHeadIcon(name, "INCOME")))
        db.execSQL("UPDATE categories SET icon=? WHERE name=? AND kind='INCOME' AND icon=''", arrayOf<Any>(defaultHeadIcon(name, "INCOME"), name))
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `pending` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `pkg` TEXT NOT NULL, `label` TEXT NOT NULL, `accountId` INTEGER NOT NULL, `amount` REAL, `merchant` TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `recurring` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `category` TEXT NOT NULL, `accountId` INTEGER NOT NULL, `toAccountId` INTEGER, `tag` TEXT NOT NULL, `excludeFromBudget` INTEGER NOT NULL, `freq` TEXT NOT NULL, `nextDue` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`name` TEXT NOT NULL, `kind` TEXT NOT NULL, PRIMARY KEY(`name`, `kind`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `daymarks` (`date` INTEGER NOT NULL, PRIMARY KEY(`date`))")
        seedCategories(db)
        db.execSQL("INSERT OR IGNORE INTO categories(name,kind) SELECT DISTINCT category,'EXPENSE' FROM transactions WHERE type='EXPENSE'")
        db.execSQL("INSERT OR IGNORE INTO categories(name,kind) SELECT DISTINCT category,'INCOME' FROM transactions WHERE type='INCOME'")
    }
}

/** v3: account card colours, transaction creation time (for time-of-day suggestions), head icons. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transactions ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
    }
}

/** v4: people & splits, trip routes, itemised bills. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `persons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
        db.execSQL("ALTER TABLE transactions ADD COLUMN personId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transactions ADD COLUMN owed REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transactions ADD COLUMN routeFrom TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE transactions ADD COLUMN routeTo TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE transactions ADD COLUMN items TEXT NOT NULL DEFAULT ''")
    }
}

/** v5: person details and the larger set of well-known heads. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE persons ADD COLUMN relation TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE persons ADD COLUMN phone TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE persons ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        seedCategories(db)
    }
}

/**
 * v6: recycle bin, local change history, account archive/default flags,
 * predefined-head repair with icons, and removal of Portfolio accounts.
 * Existing ASSET accounts are converted to BANK so no transaction is lost.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE accounts SET type='BANK' WHERE type='ASSET'")
        db.execSQL("CREATE TABLE IF NOT EXISTS `deleted_txns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `merchant` TEXT NOT NULL, `note` TEXT NOT NULL, `date` INTEGER NOT NULL, `category` TEXT NOT NULL, `accountId` INTEGER NOT NULL, `toAccountId` INTEGER, `tag` TEXT NOT NULL, `excludeFromBudget` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `personId` INTEGER NOT NULL, `owed` REAL NOT NULL, `routeFrom` TEXT NOT NULL, `routeTo` TEXT NOT NULL, `items` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `change_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entity` TEXT NOT NULL, `entityId` INTEGER NOT NULL, `action` TEXT NOT NULL, `payload` TEXT NOT NULL, `changedAt` INTEGER NOT NULL)")
        seedCategoriesWithIcons(db)
    }
}

/** v7: local person photos and rare occasion groups. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE persons ADD COLUMN photoUri TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `occasion_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `group_members` (`groupId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `personId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `group_debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `fromPersonId` INTEGER NOT NULL, `toPersonId` INTEGER NOT NULL, `amount` REAL NOT NULL, `settled` INTEGER NOT NULL DEFAULT 0)")
    }
}

/** v8: full trips, their people/debts, and a safe transaction-to-trip link. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN tripId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE deleted_txns ADD COLUMN tripId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `place` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, `budget` REAL NOT NULL, `note` TEXT NOT NULL, `archived` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, `spent` REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trip_members` (`tripId` INTEGER NOT NULL, `personId` INTEGER NOT NULL, PRIMARY KEY(`tripId`, `personId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trip_debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `fromPersonId` INTEGER NOT NULL, `toPersonId` INTEGER NOT NULL, `amount` REAL NOT NULL, `settled` INTEGER NOT NULL DEFAULT 0, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
    }
}

@Database(
    entities = [Account::class, Txn::class, Budget::class, Pending::class, Recurring::class,
        Category::class, DayMark::class, Person::class, DeletedTxn::class, ChangeHistory::class,
        OccasionGroup::class, GroupMember::class, GroupDebt::class,
        Trip::class, TripMember::class, TripDebt::class],
    version = 8, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "ledger.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance,color,archived,isDefault) VALUES('Cash','CASH',0.0,2,0,1)")
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance,color,archived,isDefault) VALUES('Bank Account','BANK',0.0,0,0,0)")
                        seedCategoriesWithIcons(db)
                    }
                })
                .build().also { inst = it }
        }
    }
}
