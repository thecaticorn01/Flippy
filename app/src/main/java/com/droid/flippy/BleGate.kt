package com.droid.flippy

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droid.flippy.shizuku.ShizukuManager
import com.droid.flippy.shizuku.ShizukuToggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared gate for every BLE-spam toggle in the app (home quick buttons,
 * platform submenu items, plugin modes).
 *
 * Fixes two long-standing "toggle does nothing" bugs:
 * 1. The old code fired the system "enable Bluetooth" dialog and then
 *    dropped the pending toggle — after enabling BT nothing started.
 *    The gate remembers the action and runs it when the dialog returns OK.
 * 2. Missing runtime permissions made spam fail silently while the button
 *    flipped to "active". The gate checks everything first via
 *    [BleSpamController.check] and shows the reason in a toast instead.
 *
 * Usage: `val gate = rememberBleGate()` then `gate.acquire { /* start */ }`.
 */
class BleGate internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val failMessage: (BlePreflight) -> String,
    private val setPending: ((() -> Unit)?) -> Unit,
    private val launchPermissions: () -> Unit,
    private val launchBtEnable: () -> Unit,
) {
    fun acquire(action: () -> Unit) {
        when (val check = BleSpamController.check(context)) {
            BlePreflight.OK -> action()
            BlePreflight.BT_DISABLED -> {
                if (ShizukuManager.isReady()) {
                    // Silent enable via shell privileges — no system dialog.
                    // Falls back to the dialog if it doesn't stick.
                    scope.launch(Dispatchers.IO) {
                        val ok = ShizukuToggles.setBluetooth(true)
                        withContext(Dispatchers.Main) {
                            if (ok && BleSpamController.check(context) == BlePreflight.OK) {
                                action()
                            } else {
                                setPending(action)
                                launchBtEnable()
                            }
                        }
                    }
                } else {
                    setPending(action)
                    launchBtEnable()
                }
            }
            BlePreflight.NO_PERMISSION -> {
                setPending(action)
                launchPermissions()
            }
            else -> FlippyToast.show(failMessage(check))
        }
    }
}

@Composable
fun rememberBleGate(): BleGate {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needBtMessage = stringResource(R.string.ble_spam_need_bt)
    val needPermissionMessage = stringResource(R.string.ble_permission_advertising_required)
    val noAdvertiserMessage = stringResource(R.string.ble_spam_no_advertiser)
    val startFailedMessage = stringResource(R.string.ble_spam_start_failed)

    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun messageFor(result: BlePreflight): String = when (result) {
        BlePreflight.BT_DISABLED -> needBtMessage
        BlePreflight.NO_PERMISSION -> needPermissionMessage
        BlePreflight.NO_ADAPTER, BlePreflight.NO_ADVERTISER -> noAdvertiserMessage
        else -> startFailedMessage
    }

    fun runPending() {
        val action = pending
        pending = null
        if (action == null) return
        when (val check = BleSpamController.check(context)) {
            BlePreflight.OK -> action()
            else -> FlippyToast.show(messageFor(check))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            runPending()
        } else {
            pending = null
            FlippyToast.show(needPermissionMessage)
        }
    }

    val btEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runPending()
        } else {
            pending = null
            FlippyToast.show(needBtMessage)
        }
    }

    return remember(context) {
        BleGate(
            context = context,
            scope = scope,
            failMessage = ::messageFor,
            setPending = { pending = it },
            launchPermissions = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                } else {
                    pending = null
                    FlippyToast.show(needPermissionMessage)
                }
            },
            launchBtEnable = {
                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
        )
    }
}
