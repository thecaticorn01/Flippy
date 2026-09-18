package com.droid.flippy.shizuku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.flippy.FlippySwitch
import com.droid.flippy.FlippyToast
import com.droid.flippy.M3SegmentedListItemSpacing
import com.droid.flippy.MaterialCard
import com.droid.flippy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → System control: Shizuku status plus radio toggles that work
 * through shell privileges (adb-started Shizuku, no root required).
 */
@Composable
fun ShizukuSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuState by ShizukuManager.state.collectAsState()
    val ready = shizukuState == ShizukuManager.State.READY

    var busy by remember { mutableStateOf(false) }
    var bt by remember { mutableStateOf<Boolean?>(null) }
    var wifi by remember { mutableStateOf<Boolean?>(null) }
    var nfc by remember { mutableStateOf<Boolean?>(null) }
    var airplane by remember { mutableStateOf<Boolean?>(null) }
    val failedMessage = stringResource(R.string.shizuku_op_failed)

    fun refreshAll() {
        if (!ShizukuManager.isReady()) {
            bt = null
            wifi = null
            nfc = null
            airplane = null
            return
        }
        scope.launch(Dispatchers.IO) {
            val b = ShizukuToggles.bluetoothEnabled()
            val w = ShizukuToggles.wifiEnabled()
            val a = ShizukuToggles.airplaneEnabled()
            val n = ShizukuToggles.nfcEnabled(context)
            withContext(Dispatchers.Main) {
                bt = b
                wifi = w
                airplane = a
                nfc = n
            }
        }
    }

    LaunchedEffect(shizukuState) {
        ShizukuManager.refresh()
        refreshAll()
    }

    fun toggle(current: Boolean?, op: suspend () -> Boolean) {
        val before = current ?: return
        if (busy || !ready) return
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!op()) {
                    withContext(Dispatchers.Main) {
                        FlippyToast.show(failedMessage)
                    }
                }
            } finally {
                refreshAll()
                busy = false
            }
        }
    }

    val statusText = when (shizukuState) {
        ShizukuManager.State.READY -> stringResource(R.string.shizuku_status_ready)
        ShizukuManager.State.DENIED -> stringResource(R.string.shizuku_status_denied)
        ShizukuManager.State.NO_DAEMON -> stringResource(R.string.shizuku_status_off)
        ShizukuManager.State.UNKNOWN -> stringResource(R.string.shizuku_status_off)
    }

    Column(verticalArrangement = Arrangement.spacedBy(M3SegmentedListItemSpacing)) {
        Text(
            text = stringResource(R.string.shizuku_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        MaterialCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            contentPadding = 0.dp
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (shizukuState == ShizukuManager.State.DENIED) {
                        TextButton(onClick = { ShizukuManager.requestPermission() }) {
                            Text(stringResource(R.string.ir_transmitter_permission))
                        }
                    } else {
                        TextButton(onClick = {
                            ShizukuManager.refresh()
                            refreshAll()
                        }) {
                            Text("↻")
                        }
                    }
                }
                if (!ready) {
                    Text(
                        text = stringResource(R.string.shizuku_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                val rows = listOf(
                    Triple(stringResource(R.string.nav_bluetooth), bt, { v: Boolean ->
                        toggle(bt) { ShizukuToggles.setBluetooth(v) }
                    }),
                    Triple(stringResource(R.string.shizuku_wifi), wifi, { v: Boolean ->
                        toggle(wifi) { ShizukuToggles.setWifi(v) }
                    }),
                    Triple(stringResource(R.string.shizuku_nfc), nfc, { v: Boolean ->
                        toggle(nfc) { ShizukuToggles.setNfc(context, v) }
                    }),
                    Triple(stringResource(R.string.shizuku_airplane), airplane, { v: Boolean ->
                        toggle(airplane) { ShizukuToggles.setAirplane(v) }
                    }),
                )
                rows.forEach { (label, state, onChange) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        FlippySwitch(
                            checked = state == true,
                            onCheckedChange = onChange,
                            enabled = ready && !busy && state != null
                        )
                    }
                }
            }
        }
    }
}
