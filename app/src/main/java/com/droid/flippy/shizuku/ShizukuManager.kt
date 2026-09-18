package com.droid.flippy.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.droid.flippy.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * First-party Shizuku integration: daemon/permission state plus shell
 * command execution. Everything here works with adb-started (non-root)
 * Shizuku — it only relies on shell-UID privileges (shell commands such as
 * `svc`, `settings`, `cmd`), never on root.
 *
 * Lifecycle: [init] once from the Application; state is exposed as a
 * [StateFlow] so Settings and feature screens react to the daemon
 * starting/stopping and to permission grants without an Activity.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val REQUEST_CODE = 9001

    data class ShellResult(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    enum class State {
        UNKNOWN,
        READY,
        NO_DAEMON,
        DENIED,
    }

    private val _state = MutableStateFlow(State.UNKNOWN)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Log.i(TAG, "binder received")
                    refresh()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "binder listener", t)
        }
        try {
            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() {
                    Log.i(TAG, "binder dead")
                    refresh()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "dead listener", t)
        }
        try {
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Log.i(TAG, "permission result code=$requestCode granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
                    refresh()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "permission listener", t)
        }
        refresh()
    }

    fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = State.NO_DAEMON
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED)
        _state.value = if (granted == PackageManager.PERMISSION_GRANTED) State.READY else State.DENIED
    }

    fun isReady(): Boolean = _state.value == State.READY

    /** Shows the Shizuku permission dialog (via the Shizuku app). No root needed. */
    fun requestPermission() {
        try {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                Log.w(TAG, "requestPermission: no daemon")
                refresh()
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission", t)
        }
    }

    /**
     * Runs a shell command as shell UID via Shizuku. The command must be
     * non-interactive and bounded; a timeout is enforced. Safe to call from
     * any thread (dispatches to IO).
     */
    suspend fun exec(command: String, timeoutSec: Long = 20): ShellResult =
        withContext(Dispatchers.IO) {
            if (!isReady()) {
                refresh()
                if (!isReady()) return@withContext ShellResult(-1, "", "Shizuku not ready")
            }
            try {
                val raw = ShizukuHelper.runShellCommandWithOutput(command, timeoutSec)
                val json = JSONObject(raw)
                ShellResult(
                    code = json.optInt("code", -1),
                    out = json.optString("out", ""),
                    err = json.optString("err", "")
                )
            } catch (t: Throwable) {
                Log.w(TAG, "exec failed: $command", t)
                ShellResult(-1, "", t.message ?: "exec failed")
            }
        }

    /** Parses `settings get` output ("1"/"0") into Boolean, null when unknown. */
    internal fun parseSettingsFlag(out: String): Boolean? = when (out.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
