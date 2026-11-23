package com.minimal.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Account::class, Txn::class, Budget::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(c: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDatabase::class.java, "ledger.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance) VALUES('Cash','CASH',0.0)")
                        db.execSQL("INSERT INTO accounts(name,type,openingBalance) VALUES('Bank Account','BANK',0.0)")
                    }
                })
                .build().also { inst = it }
        }
    }
}
