package com.droid.flippy

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.droid.flippy.ir.usb.UsbIrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transmitter selector card: built-in blaster status, USB dongle discovery,
 * permission, connect/disconnect and route (Auto / Built-in / USB).
 *
 * Shown at the top of the IR home screens. All USB state comes from
 * [UsbIrManager.state]; blocking calls run on Dispatchers.IO.
 */
@Composable
fun IrTransmitterCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbState by UsbIrManager.state.collectAsState()

    val hasBuiltIn = remember {
        runCatching { IrBackend.hasBuiltInEmitter(context) }.getOrDefault(false)
    }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { UsbIrManager.refresh() }
    }

    fun connect(device: UsbDevice? = null) {
        if (busy) return
        busy = true
        UsbIrManager.clearError()
        scope.launch(Dispatchers.IO) {
            try {
                UsbIrManager.connect(device)
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                UsbIrManager.disconnect()
            } finally {
                busy = false
            }
        }
    }

    MaterialCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ir_transmitter_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasBuiltIn) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (hasBuiltIn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (hasBuiltIn) R.string.ir_transmitter_builtin_ok
                        else R.string.ir_transmitter_builtin_missing
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (usbState.isOpen && usbState.connectedLabel != null) {
                Text(
                    text = stringResource(
                        R.string.ir_transmitter_usb_connected,
                        usbState.connectedLabel +
                            (usbState.connectedProtocol?.let { " · $it" } ?: "")
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { disconnect() }, enabled = !busy) {
                        Text(stringResource(R.string.ir_transmitter_disconnect))
                    }
                }
            } else {
                val visible = usbState.devices.take(3)
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ir_transmitter_usb_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    visible.forEach { info ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = info.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    text = info.vidPid +
                                        if (!info.isSupported && info.isCandidate) " · generic" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!info.hasPermission) {
                                TextButton(
                                    onClick = { UsbIrManager.requestPermission(info.device) },
                                    enabled = !busy
                                ) {
                                    Text(stringResource(R.string.ir_transmitter_permission))
                                }
                            }
                            Button(
                                onClick = { connect(info.device) },
                                enabled = !busy && (info.isSupported || usbState.allowGeneric)
                            ) {
                                Text(stringResource(R.string.ir_transmitter_connect))
                            }
                        }
                    }
                }
                if (usbState.devices.any { it.isSupported && it.hasPermission }) {
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { connect() }, enabled = !busy) {
                            Text(stringResource(R.string.ir_transmitter_connect))
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                RouteChip(
                    selected = usbState.route == UsbIrManager.Route.AUTO,
                    label = stringResource(R.string.ir_transmitter_route_auto),
                    onClick = { UsbIrManager.setRoute(UsbIrManager.Route.AUTO) }
                )
                RouteChip(
                    selected = usbState.route == UsbIrManager.Route.BUILT_IN_ONLY,
                    label = stringResource(R.string.ir_transmitter_route_builtin),
                    onClick = { UsbIrManager.setRoute(UsbIrManager.Route.BUILT_IN_ONLY) }
                )
                RouteChip(
                    selected = usbState.route == UsbIrManager.Route.USB_ONLY,
                    label = stringResource(R.string.ir_transmitter_route_usb),
                    onClick = { UsbIrManager.setRoute(UsbIrManager.Route.USB_ONLY) }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = usbState.allowGeneric,
                    onCheckedChange = { UsbIrManager.setAllowGeneric(it) }
                )
                Text(
                    text = stringResource(R.string.ir_transmitter_allow_generic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            usbState.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = stringResource(R.string.ir_transmitter_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RouteChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) }
    )
}
