package com.mtracker.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val DEFAULT_EXPENSE_CATEGORIES = listOf(
    "Food & Dining", "Coffee & Snacks", "Groceries", "Auto & Transport", "Gas & Fuel", "Travel",
    "Bills & Utilities", "Phone & Internet", "Rent", "Shopping", "Clothing", "Entertainment",
    "Online Subscriptions", "Personal Care", "Health & Insurance", "Fitness", "Education", "Books",
    "Stationery", "Home & Repairs", "Gifts & Donations", "Family & Kids", "Pets", "Investments",
    "Taxes & Fees", "Other"
)
val DEFAULT_INCOME_CATEGORIES = listOf("Salary", "Refunds", "Interest", "Gifts", "Other")

private fun seedCategories(db: SupportSQLiteDatabase) {
    DEFAULT_EXPENSE_CATEGORIES.forEach { db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?, 'EXPENSE')", arrayOf<Any>(it)) }
    DEFAULT_INCOME_CATEGORIES.forEach { db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?, 'INCOME')", arrayOf<Any>(it)) }
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transactions ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [Account::class, Txn::class, Budget::class, Pending::class, Recurring::class, Category::class, DayMark::class, Person::class],
    version = 4, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "ledger.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance,color) VALUES('Cash','CASH',0.0,2)")
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance,color) VALUES('Bank Account','BANK',0.0,0)")
                        seedCategories(db)
                    }
                })
                .build().also { inst = it }
        }
    }
}
