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
import com.droid.flippy.RootUtils
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

    private var masterKeyJob: kotlinx.coroutines.Job? = null

    
    fun startMasterKey() {
        if (_masterKeyRunning.value) return
        _masterKeyRunning.value = true
        _masterKeyLogs.value = emptyList()
        masterKeyJob = viewModelScope.launch {
            val rooted = RootNfcHelper.hasRoot()
            // Rootless path: Shizuku (shell UID, no root) can still run
            // `svc nfc enable` to force NFC on. Best-effort, never blocks.
            var shizukuNfc = false
            if (!rooted) {
                shizukuNfc = runCatching {
                    withContext(Dispatchers.IO) {
                        com.droid.flippy.shizuku.ShizukuManager.refresh()
                        if (com.droid.flippy.shizuku.ShizukuManager.isReady()) {
                            com.droid.flippy.shizuku.ShizukuToggles.setNfc(app, true)
                        } else false
                    }
                }.getOrDefault(false)
            } else {
                withContext(Dispatchers.IO) {
                    RootNfcHelper.prepareForRead(app)
                    RootUtils.executeRootCommand("cmd nfc enable")
                    RootUtils.executeRootCommand("svc nfc enable")
                }
            }
            when {
                rooted -> addMasterKeyLog("Root OK — NFC forced on, brute intercom/NFC locks")
                shizukuNfc -> addMasterKeyLog("Shizuku OK — NFC forced on without root; tap a Mifare Classic tag")
                com.droid.flippy.shizuku.ShizukuManager.isReady() ->
                    addMasterKeyLog("Shizuku ready — tap a Mifare Classic tag for rootless dictionary check")
                else -> addMasterKeyLog("Rootless mode — tap a Mifare Classic tag; dictionary check needs no root")
            }

            val keys = MifareClassicKeys.DICTIONARY_HEX
            val targets = listOf(
                "Mifare Classic sector",
                "Intercom lock (Classic)",
                "NFC door reader",
                "Ultralight auth",
                "Type A crypto1",
            )
            var keyIndex = 0
            var targetIndex = 0

            while (_masterKeyRunning.value) {
                val target = targets[targetIndex % targets.size]
                addMasterKeyLog("Target: $target — hold tag to phone for real check")
                repeat(8) {
                    if (!_masterKeyRunning.value) return@repeat
                    val key = keys[keyIndex % keys.size]
                    keyIndex++
                    _currentKey.value = key
                    addMasterKeyLog("Brute key $key")
                    delay(180)
                }
                targetIndex++
                delay(250)
            }
        }
    }

    /**
     * Rootless real check: tries the built-in dictionary against every
     * sector of a tapped Mifare Classic tag via the public SDK
     * (authenticateSectorWithKeyA/B). No root, no Shizuku needed.
     * Call while [masterKeyRunning] is true; results go to [masterKeyLogs].
     */
    fun onMasterKeyTag(tag: Tag) {
        if (!_masterKeyRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val classic = android.nfc.tech.MifareClassic.get(tag)
            if (classic == null) {
                addMasterKeyLog("Tag is not Mifare Classic — UID only")
                return@launch
            }
            val keys = MifareClassicKeys.DICTIONARY_HEX
            val keyBytes = keys.map { runCatching { MifareClassicKeys.toKeyBytes(it) }.getOrNull() }
            var found = 0
            var sectors = 0
            try {
                classic.connect()
                sectors = classic.sectorCount
                addMasterKeyLog("Mifare Classic: $sectors sectors, trying ${keys.size} keys…")
                for (sector in 0 until sectors) {
                    if (!_masterKeyRunning.value) break
                    var hit: String? = null
                    var hitType = ""
                    for (i in keys.indices) {
                        val kb = keyBytes[i] ?: continue
                        if (runCatching { classic.authenticateSectorWithKeyA(sector, kb) }.getOrDefault(false)) {
                            hit = keys[i]; hitType = "A"; break
                        }
                        if (runCatching { classic.authenticateSectorWithKeyB(sector, kb) }.getOrDefault(false)) {
                            hit = keys[i]; hitType = "B"; break
                        }
                    }
                    if (hit != null) {
                        found++
                        _currentKey.value = hit
                        addMasterKeyLog("Sector $sector: Key$hitType $hit ✓")
                    } else {
                        addMasterKeyLog("Sector $sector: no dictionary key")
                    }
                }
                addMasterKeyLog("Done: $found/$sectors sectors matched")
            } catch (e: Exception) {
                addMasterKeyLog("Classic read failed: ${e.message?.take(60)}")
            } finally {
                runCatching { classic.close() }
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

