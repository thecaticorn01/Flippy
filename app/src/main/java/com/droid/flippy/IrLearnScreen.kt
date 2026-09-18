package com.droid.flippy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.droid.flippy.ir.usb.UsbIrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LearnPhase { NeedDongle, Ready, Listening, Captured }

private val LEARN_FREQUENCIES = listOf(30000, 36000, 38000, 40000, 56000)
private const val LEARN_TIMEOUT_MS = 60_000L

/**
 * "Learn New Remote" (Flipper Zero style): captures button presses from a
 * physical remote through a learning-capable USB IR dongle (ElkSmart
 * family) and saves them as a user remote. Built-in blasters are
 * transmit-only, so learning requires USB.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IrLearnScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbState by UsbIrManager.state.collectAsState()

    var phase by remember { mutableStateOf(LearnPhase.NeedDongle) }
    var learnSupported by remember { mutableStateOf<Boolean?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var listenJob by remember { mutableStateOf<Job?>(null) }

    var captured by remember { mutableStateOf<IntArray?>(null) }
    var buttonName by remember { mutableStateOf("") }
    var remoteName by remember { mutableStateOf("") }
    var frequency by remember { mutableIntStateOf(38000) }
    var learnedCount by remember { mutableIntStateOf(0) }
    var learnedRemoteId by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    val defaultButtonName = stringResource(R.string.ir_learn_button_default, learnedCount + 1)
    val timeoutMessage = stringResource(R.string.ir_learn_timeout)
    val unsupportedMessage = stringResource(R.string.ir_learn_unsupported)

    LaunchedEffect(usbState.isOpen, usbState.connectedProtocol) {
        learnSupported = if (usbState.isOpen) UsbIrManager.isLearningSupported() else null
        if (!usbState.isOpen) {
            listenJob?.cancel()
            listenJob = null
            phase = LearnPhase.NeedDongle
        } else if (phase == LearnPhase.NeedDongle) {
            phase = LearnPhase.Ready
        }
    }

    LaunchedEffect(phase) {
        if (phase == LearnPhase.Listening) {
            elapsed = 0
            while (phase == LearnPhase.Listening) {
                delay(1000)
                elapsed++
            }
        }
    }

    fun startListening() {
        if (busy) return
        statusMessage = null
        captured = null
        buttonName = ""
        phase = LearnPhase.Listening
        listenJob?.cancel()
        listenJob = scope.launch(Dispatchers.IO) {
            val result = UsbIrManager.learnButton(LEARN_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                listenJob = null
                when (result) {
                    is UsbIrManager.LearnResult.Captured -> {
                        if (result.patternUs.size >= 2) {
                            captured = result.patternUs
                            phase = LearnPhase.Captured
                        } else {
                            statusMessage = timeoutMessage
                            phase = LearnPhase.Ready
                        }
                    }
                    is UsbIrManager.LearnResult.Timeout -> {
                        statusMessage = timeoutMessage
                        phase = LearnPhase.Ready
                    }
                    is UsbIrManager.LearnResult.Unavailable -> {
                        statusMessage = result.reason
                        phase = if (usbState.isOpen) LearnPhase.Ready else LearnPhase.NeedDongle
                    }
                    is UsbIrManager.LearnResult.Error -> {
                        statusMessage = result.message
                        phase = LearnPhase.Ready
                    }
                }
            }
        }
    }

    fun cancelListening() {
        listenJob?.cancel()
        listenJob = null
        phase = LearnPhase.Ready
    }

    fun save(finish: Boolean) {
        val pattern = captured ?: return
        if (busy) return
        busy = true
        val finalButton = IrButton(
            name = buttonName.ifBlank { defaultButtonName },
            frequency = frequency,
            pattern = pattern
        )
        scope.launch(Dispatchers.IO) {
            UserIrRemoteStore.ensureLoaded(context)
            var id = learnedRemoteId
            if (id == null) {
                val created = UserIrRemoteStore.addRemote(
                    context,
                    remoteName.ifBlank { "Learned" },
                    listOf(finalButton)
                )
                id = created.id
            } else {
                UserIrRemoteStore.appendCommands(context, id, listOf(finalButton))
            }
            val savedId = id
            withContext(Dispatchers.Main) {
                busy = false
                learnedRemoteId = savedId
                learnedCount++
                captured = null
                buttonName = ""
                if (finish) {
                    navController.navigate("other/user_ir_remote/$savedId")
                } else {
                    phase = LearnPhase.Ready
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionTopBar(
            title = stringResource(R.string.ir_learn_title),
            onBack = { navController.popBackStack() },
            transparent = true
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentPadding = PaddingValues(bottom = 220.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                IrTransmitterCard(modifier = Modifier.padding(bottom = 4.dp))
            }
            item {
                MaterialCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ir_learn_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (learnedCount > 0) {
                            Text(
                                text = stringResource(R.string.ir_learn_saved_count, learnedCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        statusMessage?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            when (phase) {
                LearnPhase.NeedDongle -> {
                    item {
                        Text(
                            text = stringResource(R.string.ir_learn_need_dongle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LearnPhase.Ready -> {
                    item {
                        TextField(
                            value = remoteName,
                            onValueChange = { remoteName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ir_learn_remote_name)) },
                            singleLine = true,
                            enabled = learnedRemoteId == null
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.ir_learn_frequency),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LEARN_FREQUENCIES.forEach { freq ->
                                FilterChip(
                                    selected = frequency == freq,
                                    onClick = { frequency = freq },
                                    label = { Text("${freq / 1000}k") }
                                )
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = { startListening() },
                            enabled = usbState.isOpen && learnSupported == true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.ir_learn_start))
                        }
                    }
                    if (usbState.isOpen && learnSupported == false) {
                        item {
                            Text(
                                text = unsupportedMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                LearnPhase.Listening -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.ir_learn_listening, elapsed),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    item {
                        TextButton(
                            onClick = { cancelListening() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.ir_learn_cancel))
                        }
                    }
                }
                LearnPhase.Captured -> {
                    val pattern = captured
                    if (pattern != null) {
                        val totalMs = (pattern.sumOf { it.toLong() } / 1000L).toInt()
                        item {
                            Text(
                                text = stringResource(R.string.ir_learn_captured, pattern.size, totalMs),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        item {
                            TextField(
                                value = buttonName,
                                onValueChange = { buttonName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.ir_learn_button_name)) },
                                placeholder = { Text(defaultButtonName) },
                                singleLine = true
                            )
                        }
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { save(finish = false) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.ir_learn_save_next))
                                }
                                Button(
                                    onClick = { save(finish = true) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.ir_learn_save_done))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
