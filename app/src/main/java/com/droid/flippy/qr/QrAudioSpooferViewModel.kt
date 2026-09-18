package com.droid.flippy.qr

import android.app.Application
import android.content.*
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droid.flippy.R
import com.droid.flippy.nfc.NfcAudioSpoofState
import com.droid.flippy.qr.server.KtorServerManager
import com.droid.flippy.qr.server.QrSpooferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class QrAudioSpooferViewModel(application: Application) : AndroidViewModel(application) {
    private var startJob: Job? = null

    private fun s(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun pushLog(text: String, limit: Int = 80) {
        _logs.value = (listOf(text) + _logs.value).take(limit)
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp: StateFlow<String?> = _serverIp.asStateFlow()

    private val _isStarted = MutableStateFlow(false)
    val isStarted: StateFlow<Boolean> = _isStarted.asStateFlow()

    private val _deviceCount = MutableStateFlow(0)
    val deviceCount: StateFlow<Int> = _deviceCount.asStateFlow()

    private val _customAudioList = MutableStateFlow<List<String>>(emptyList())
    val customAudioList: StateFlow<List<String>> = _customAudioList.asStateFlow()

    private val _pageType = MutableStateFlow("flippy")
    val pageType: StateFlow<String> = _pageType

    private val _customHtmlName = MutableStateFlow<String?>(null)
    val customHtmlName: StateFlow<String?> = _customHtmlName.asStateFlow()

    private val _showFirstTimeInfo = MutableStateFlow(false)
    val showFirstTimeInfo: StateFlow<Boolean> = _showFirstTimeInfo.asStateFlow()

    val isCustomHtmlMode: Boolean
        get() = _pageType.value == KtorServerManager.PAGE_CUSTOM_HTML

    init {
        val prefs = application.getSharedPreferences("qr_prefs", Context.MODE_PRIVATE)
        _pageType.value = prefs.getString("page_type", "flippy") ?: "flippy"
        _customHtmlName.value = prefs.getString("custom_html_name", null)

        if (!prefs.getBoolean("audio_spoof_info_shown", false)) {
            _showFirstTimeInfo.value = true
        }

        loadCustomAudioList()
        applyCustomHtmlToServer(QrSpooferService.serverManager)


        QrSpooferService.serverManager?.let { sm ->
            observeServer(sm)
            _isStarted.value = true
            _serverIp.value = sm.getServerUrl()?.removePrefix("https://")?.removePrefix("http://")
        }
    }

    private fun customHtmlFile(): File =
        File(getApplication<Application>().filesDir, "qr_spoofer/custom_page.html")

    private fun applyCustomHtmlToServer(sm: KtorServerManager?) {
        if (sm == null) return
        val f = customHtmlFile()
        sm.customHtml = if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    fun importHtml(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "imported.html"
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalStateException(s(R.string.audio_spoof_err_read))
                if (text.isBlank()) throw IllegalStateException(s(R.string.audio_spoof_err_empty_html))

                val dir = File(context.filesDir, "qr_spoofer")
                if (!dir.exists()) dir.mkdirs()
                customHtmlFile().writeText(text, Charsets.UTF_8)

                context.getSharedPreferences("qr_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("page_type", KtorServerManager.PAGE_CUSTOM_HTML)
                    .putString("custom_html_name", name)
                    .apply()

                launch(Dispatchers.Main) {
                    _customHtmlName.value = name
                    _pageType.value = KtorServerManager.PAGE_CUSTOM_HTML
                    applyCustomHtmlToServer(QrSpooferService.serverManager)
                    pushLog(s(R.string.audio_spoof_imported, name, text.length))
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    pushLog(s(R.string.audio_spoof_err_import, e.message ?: ""))
                }
            }
        }
    }

    private fun loadCustomAudioList() {
        val dir = File(getApplication<Application>().filesDir, "qr_spoofer/audio")
        if (dir.exists()) {
            _customAudioList.value = dir.listFiles()?.map { it.name } ?: emptyList()
        }
    }

    fun addCustomAudio(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val fileName = "custom_${System.currentTimeMillis()}.mp3"
                val dir = File(context.filesDir, "qr_spoofer/audio")
                if (!dir.exists()) dir.mkdirs()

                val file = File(dir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                launch(Dispatchers.Main) {
                    loadCustomAudioList()
                    pushLog(s(R.string.audio_spoof_added, fileName), 50)
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    pushLog(s(R.string.audio_spoof_err_add, e.message ?: ""), 50)
                }
            }
        }
    }

    private fun observeServer(sm: KtorServerManager) {
        viewModelScope.launch {
            sm.logs.collect { message ->
                _logs.value = (listOf(message) + _logs.value).take(80)
            }
        }
        viewModelScope.launch {
            sm.deviceCount.collect { count ->
                _deviceCount.value = count
            }
        }
    }

    fun dismissFirstTimeInfo() {
        _showFirstTimeInfo.value = false
        getApplication<Application>().getSharedPreferences("qr_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("audio_spoof_info_shown", true).apply()
    }

    fun setPageType(type: String) {
        if (type == KtorServerManager.PAGE_CUSTOM_HTML) {
            val f = customHtmlFile()
            if (!f.exists()) {
                pushLog(s(R.string.audio_spoof_need_html))
                return
            }
        }
        _pageType.value = type
        getApplication<Application>().getSharedPreferences("qr_prefs", Context.MODE_PRIVATE)
            .edit().putString("page_type", type).apply()
        applyCustomHtmlToServer(QrSpooferService.serverManager)
        if (type != KtorServerManager.PAGE_CUSTOM_HTML) {
            sendCommand("CHANGE_PAGE:$type")
        }
    }

    fun startServer() {
        if (_isStarted.value && QrSpooferService.serverManager != null) {
            pushLog(s(R.string.audio_spoof_running))
            return
        }

        val localIp = getLocalIpAddress()
        if (localIp == null) {
            _logs.value = listOf(
                s(R.string.audio_spoof_no_net),
                s(R.string.audio_spoof_no_net_connect),
                s(R.string.audio_spoof_no_net_offline),
            )
            return
        }

        _isStarted.value = true
        _serverIp.value = null
        _logs.value = listOf(
            s(R.string.audio_spoof_starting),
            s(R.string.audio_spoof_local_only),
            s(R.string.audio_spoof_no_tunnels),
        )

        startJob?.cancel()
        startJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sm = QrSpooferService.serverManager ?: KtorServerManager(getApplication()).also {
                    QrSpooferService.serverManager = it
                }
                applyCustomHtmlToServer(sm)

                launch(Dispatchers.Main) {
                    observeServer(sm)
                }

                val startedPort = sm.start(8080)
                if (startedPort == -1) {
                    launch(Dispatchers.Main) {
                        _logs.value = (
                            listOf(
                                s(R.string.audio_spoof_err_start),
                                s(R.string.audio_spoof_ports_busy),
                            ) + _logs.value
                            ).take(80)
                        _isStarted.value = false
                    }
                    return@launch
                }
                val localUrl = "http://$localIp:$startedPort"
                sm.setServerUrl(localUrl)
                NfcAudioSpoofState.startSpoofing(localUrl)
                launch(Dispatchers.Main) {
                    _serverIp.value = "$localIp:$startedPort"
                    _logs.value = (
                        listOf(
                            s(R.string.audio_spoof_ready, localUrl),
                            s(R.string.audio_spoof_lan_only),
                        ) + _logs.value
                        ).take(80)
                }


                try {
                    val intent = Intent(getApplication(), QrSpooferService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        getApplication<Application>().startForegroundService(intent)
                    } else {
                        getApplication<Application>().startService(intent)
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) {
                        _logs.value = (listOf("FGS: ${e.message}") + _logs.value).take(80)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    _logs.value = (listOf(s(R.string.audio_spoof_err, e.message ?: "")) + _logs.value).take(80)
                    _isStarted.value = false
                }
            }
        }
    }

    fun stopServer() {
        startJob?.cancel()
        startJob = null
        _isStarted.value = false
        _serverIp.value = null
        try {
            QrSpooferService.serverManager?.stop()
            QrSpooferService.serverManager?.setServerUrl(null)
        } catch (_: Exception) {
        }
        QrSpooferService.serverManager = null
        NfcAudioSpoofState.stopSpoofing()
        try {
            val intent = Intent(getApplication(), QrSpooferService::class.java)
            getApplication<Application>().stopService(intent)
        } catch (_: Exception) {
        }
        pushLog(s(R.string.audio_spoof_stopped))
    }

    fun sendCommand(command: String) {
        QrSpooferService.serverManager?.broadcast(command)
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val preferred = mutableListOf<String>()
            val others = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val name = networkInterface.name.lowercase()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (!host.contains('.') || host.contains(':')) continue
                    if (!address.isSiteLocalAddress && !address.isLinkLocalAddress) continue
                    if (
                        name.startsWith("wlan") || name.startsWith("ap") ||
                        name.startsWith("swlan") || name.startsWith("eth")
                    ) {
                        preferred += host
                    } else {
                        others += host
                    }
                }
            }
            preferred.firstOrNull() ?: others.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

}

