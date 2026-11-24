package com.mtracker.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val DEFAULT_EXPENSE_CATEGORIES = listOf(
    "Food & Dining", "Auto & Transport", "Gas & Fuel", "Bills & Utilities", "Rent", "Groceries",
    "Shopping", "Entertainment", "Online Subscriptions", "Personal Care", "Health & Insurance",
    "Education", "Books", "Stationery", "Home & Repairs", "Gifts & Donations", "Investments",
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
        // keep any category names you already used
        db.execSQL("INSERT OR IGNORE INTO categories(name,kind) SELECT DISTINCT category,'EXPENSE' FROM transactions WHERE type='EXPENSE'")
        db.execSQL("INSERT OR IGNORE INTO categories(name,kind) SELECT DISTINCT category,'INCOME' FROM transactions WHERE type='INCOME'")
    }
}

@Database(
    entities = [Account::class, Txn::class, Budget::class, Pending::class, Recurring::class, Category::class, DayMark::class],
    version = 2, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "ledger.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance) VALUES('Cash','CASH',0.0)")
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance) VALUES('Bank Account','BANK',0.0)")
                        seedCategories(db)
                    }
                })
                .build().also { inst = it }
        }
    }
}
