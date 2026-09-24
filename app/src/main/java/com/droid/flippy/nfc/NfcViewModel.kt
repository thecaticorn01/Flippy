package com.droid.flippy.nfc

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.Tag
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droid.flippy.trackNfcRead
import com.droid.flippy.nfc.db.NfcDatabase
import com.droid.flippy.nfc.db.NfcScanEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class NfcArmedOperation {
    data object Erase : NfcArmedOperation()
    data class WriteNdef(val message: NdefMessage) : NfcArmedOperation()
}

sealed class NfcWriteOutcome {
    data object Idle : NfcWriteOutcome()
    data object Success : NfcWriteOutcome()
    data class Failure(val message: String) : NfcWriteOutcome()
}

class NfcViewModel(private val app: Application) : AndroidViewModel(app) {
    private val dao = NfcDatabase.get(app).scans()

    val history: StateFlow<List<NfcScanEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openResultChannel = Channel<Long>(Channel.BUFFERED)
    val openResultEvents = openResultChannel.receiveAsFlow()

    private val _armedOperation = MutableStateFlow<NfcArmedOperation?>(null)
    val armedOperation: StateFlow<NfcArmedOperation?> = _armedOperation.asStateFlow()

    private val _writeOutcome = MutableStateFlow<NfcWriteOutcome>(NfcWriteOutcome.Idle)
    val writeOutcome: StateFlow<NfcWriteOutcome> = _writeOutcome.asStateFlow()


    private val _masterKeyRunning = MutableStateFlow(false)
    val masterKeyRunning = _masterKeyRunning.asStateFlow()

    private val _masterKeyLogs = MutableStateFlow<List<String>>(emptyList())
    val masterKeyLogs = _masterKeyLogs.asStateFlow()

    private val _currentKey = MutableStateFlow("")
    val currentKey = _currentKey.asStateFlow()

    private val _lastPrimaryTech = MutableStateFlow<com.droid.flippy.nfc.master.NfcTechnology?>(null)
    val lastPrimaryTech = _lastPrimaryTech.asStateFlow()

    private val _masterHits = MutableStateFlow<List<com.droid.flippy.nfc.master.CredentialHit>>(emptyList())
    val masterHits = _masterHits.asStateFlow()

    val masterCredentials: StateFlow<List<com.droid.flippy.nfc.db.NfcMasterCredential>> =
        com.droid.flippy.nfc.db.NfcDatabase.get(app).nfcMaster().observeRecent(20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var masterKeyJob: kotlinx.coroutines.Job? = null

    fun startMasterKey() {
        if (_masterKeyRunning.value) return
        _masterKeyRunning.value = true
        _masterKeyLogs.value = emptyList()
        _masterHits.value = emptyList()
        _currentKey.value = ""
        masterKeyJob = viewModelScope.launch {
            val rootCtx = com.droid.flippy.nfc.master.NfcRootRaw.prepare(app)
            when (rootCtx.via) {
                com.droid.flippy.nfc.master.NfcRootRaw.Elevation.ROOT -> addMasterKeyLog("Root OK — NFC ${if (rootCtx.nfcEnabled) "on" else "not confirmed"}${if (rootCtx.rawAccess) " · raw NCI node ${rootCtx.rawNciNode}" else ""}")
                com.droid.flippy.nfc.master.NfcRootRaw.Elevation.SHIZUKU -> addMasterKeyLog("Shizuku OK — NFC ${if (rootCtx.nfcEnabled) "on" else "not confirmed"}, crypto needs no root")
                com.droid.flippy.nfc.master.NfcRootRaw.Elevation.NONE -> addMasterKeyLog("Rootless mode — NFC left to the system; brute-forcing needs no root")
            }
            val supported = com.droid.flippy.nfc.master.NfcTechnology.entries
                .filter { it != com.droid.flippy.nfc.master.NfcTechnology.UNKNOWN }
                .joinToString(" · ") { it.displayName }
            addMasterKeyLog("Master Key: $supported")
            addMasterKeyLog("Listening — tap any card. Press Stop to end.")

            var frame = 0
            while (_masterKeyRunning.value) {
                val glyphs = listOf("✻", "✹", "✺", "✻")
                _currentKey.value = glyphs[frame % glyphs.size].repeat(6).take(6)
                delay(400)
                frame++
            }
        }
    }

    /**
     * Rootless real check: interrogates the tapped tag with the multi-tech
     * engine ([NfcMasterSession]) — MIFARE Classic dictionary + sector dump,
     * NTAG/Ultralight PWD_AUTH + 3DES, DESFire/Type-4 default keys, ISO 15693
     * and ISO 14443-B mapping. Results fan out to [masterKeyLogs], [masterHits]
     * and the persistent [masterCredentials] store. No root required.
     * Call while [masterKeyRunning] is true.
     */
    fun onMasterKeyTag(tag: Tag) {
        if (!_masterKeyRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            addMasterKeyLog("Tap — UID ${tag.id?.let { com.droid.flippy.nfc.master.NfcProbeEnv.hexOf(it) } ?: "?"}")
            val report = com.droid.flippy.nfc.master.NfcMasterSession.run(
                tag = tag,
                isRunning = { _masterKeyRunning.value },
                log = { addMasterKeyLog(it) },
            )
            _lastPrimaryTech.value = report.primaryTechnology
            _masterHits.value = report.hits
            _currentKey.value = report.hits.firstOrNull()?.valueHex ?: ""

            if (report.hits.isEmpty()) {
                addMasterKeyLog("No dictionary/default credential matched")
                vibrateNfcWarn(app)
            } else {
                report.hits.forEach { hit ->
                    addMasterKeyLog("✓ ${hit.label} ${hit.valueHex}${if (hit.notes.isNotBlank()) " (${hit.notes})" else ""}")
                    runCatching {
                        com.droid.flippy.nfc.db.NfcDatabase.get(app).nfcMaster().insert(
                            com.droid.flippy.nfc.db.NfcMasterCredential(
                                capturedAtMillis = System.currentTimeMillis(),
                                uidHex = report.uidHex,
                                technology = hit.technology.name,
                                label = hit.label,
                                valueHex = hit.valueHex,
                                notes = hit.notes,
                                dumpHex = report.dumpHex.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
                vibrateNfcSuccess(app)
                addMasterKeyLog("Done — ${report.hits.size} credential(s) saved")
            }
            if (report.dumpHex.isNotBlank()) {
                addMasterKeyLog("Dump: ${report.dumpHex.lines().size} lines captured")
            }
        }
    }

    fun stopMasterKey() {
        _masterKeyRunning.value = false
        _currentKey.value = ""
        masterKeyJob?.cancel()
        addMasterKeyLog("Stopped")
    }

    private fun addMasterKeyLog(msg: String) {
        val currentList = _masterKeyLogs.value.toMutableList()
        currentList.add(0, "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg")
        if (currentList.size > 50) currentList.removeAt(currentList.size - 1)
        _masterKeyLogs.value = currentList
    }


    private var lastWriteForRetry: NdefMessage? = null

    fun scanDetails(id: Long): Flow<NfcScanEntity?> = dao.observeById(id)

    fun armErase() {
        lastWriteForRetry = null
        _writeOutcome.value = NfcWriteOutcome.Idle
        _armedOperation.value = NfcArmedOperation.Erase
    }

    fun armWrite(message: NdefMessage) {
        lastWriteForRetry = message
        _writeOutcome.value = NfcWriteOutcome.Idle
        _armedOperation.value = NfcArmedOperation.WriteNdef(message)
    }

    fun disarmWrite() {
        _armedOperation.value = null
        _writeOutcome.value = NfcWriteOutcome.Idle
    }

    fun retryLastWrite() {
        val msg = lastWriteForRetry
        if (msg != null) {
            armWrite(msg)
        } else {
            armErase()
        }
    }

    fun clearWriteFailure() {
        if (_writeOutcome.value is NfcWriteOutcome.Failure) {
            _writeOutcome.value = NfcWriteOutcome.Idle
        }
    }

    fun handleNfcIntent(intent: Intent) {
        extractTag(intent)?.let { tag ->
            try {
                com.droid.flippy.plugin.PluginManager.dispatchNfcTag(tag)
            } catch (t: Throwable) {
                android.util.Log.w("NfcViewModel", "plugin NFC dispatch", t)
            }
            // Rootless Master Key: real dictionary check on every tap while running.
            if (_masterKeyRunning.value) {
                onMasterKeyTag(tag)
            }
        }
        viewModelScope.launch {
            if (tryProcessWriterIntent(intent)) return@launch
            ingestReadIntent(intent)
        }
    }

    suspend fun tryProcessWriterIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            return false
        }
        val op = _armedOperation.value ?: return false
        val tag = extractTag(intent) ?: return false

        val result = withContext(Dispatchers.IO) {
            when (op) {
                is NfcArmedOperation.Erase -> NfcNdefWriter.eraseToEmpty(tag)
                is NfcArmedOperation.WriteNdef -> NfcNdefWriter.writeNdefMessage(tag, op.message)
            }
        }
        _armedOperation.value = null
        result.onSuccess { vibrateNfcSuccess(app) }
        _writeOutcome.value = result.fold(
            onSuccess = { NfcWriteOutcome.Success },
            onFailure = { e -> NfcWriteOutcome.Failure(e.message ?: "") },
        )
        return true
    }

    private suspend fun ingestReadIntent(intent: Intent) {
        if (RootNfcHelper.hasRoot()) {
            withContext(Dispatchers.IO) { RootNfcHelper.prepareForRead(app) }
        }
        val analyzed = NfcTagAnalyzer.analyzeIntent(
            intent,
            emptyNdefContent = app.getString(com.droid.flippy.R.string.nfc_ndef_empty),
            decodeErrorPrefix = app.getString(com.droid.flippy.R.string.nfc_decode_error),
        ) ?: return
        vibrateNfcSuccess(app)
        val id = dao.insert(analyzed)
        trackNfcRead(app)
        openResultChannel.trySend(id)
    }

    private fun extractTag(intent: Intent): Tag? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }
}

class NfcViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NfcViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NfcViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

