package com.droid.flippy.nfc.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcMasterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(credential: NfcMasterCredential): Long

    @Query("SELECT * FROM nfc_master_credentials ORDER BY capturedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<NfcMasterCredential>>

    @Query("SELECT * FROM nfc_master_credentials WHERE uidHex = :uid ORDER BY capturedAtMillis DESC LIMIT :limit")
    suspend fun forUid(uid: String, limit: Int = 20): List<NfcMasterCredential>
}