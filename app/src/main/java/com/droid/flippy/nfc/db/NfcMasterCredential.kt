package com.droid.flippy.nfc.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A credential (or key-relevant finding) recovered by the Master Key engine. */
@Entity(tableName = "nfc_master_credentials")
data class NfcMasterCredential(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val capturedAtMillis: Long,
    val uidHex: String,
    val technology: String,
    val label: String,
    val valueHex: String,
    val notes: String,
    val dumpHex: String?,
)