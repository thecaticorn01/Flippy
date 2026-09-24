package com.droid.flippy.nfc.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NfcScanEntity::class, NfcMasterCredential::class],
    version = 2,
    exportSchema = true,
)
abstract class NfcDatabase : RoomDatabase() {
    abstract fun scans(): NfcScanDao

    abstract fun nfcMaster(): NfcMasterDao

    companion object {
        @Volatile
        private var INSTANCE: NfcDatabase? = null

        fun get(context: Context): NfcDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NfcDatabase::class.java,
                    "nfc_scans.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}


