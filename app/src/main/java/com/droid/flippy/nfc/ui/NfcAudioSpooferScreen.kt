package com.droid.flippy.nfc.ui

import com.droid.flippy.FlippyIconButton

import android.app.Activity
import android.nfc.NfcAdapter
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.droid.flippy.R
import com.droid.flippy.qr.QrAudioSpooferViewModel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcAudioSpooferScreen(
    navController: NavController,
    viewModel: QrAudioSpooferViewModel = viewModel(),
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val logs by viewModel.logs.collectAsState()
    val isStarted by viewModel.isStarted.collectAsState()
    val serverIp by viewModel.serverIp.collectAsState()
    val pageType by viewModel.pageType.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val activity = context as? Activity
    val adapter = remember(activity) { activity?.let { NfcAdapter.getDefaultAdapter(it) } }
    val nfcReady = adapter != null && adapter.isEnabled

    val spoofUrl = if (serverIp != null) {
        if (serverIp!!.startsWith("http")) serverIp!! else "http://$serverIp"
    } else stringResource(R.string.audio_spoof_waiting)

    val infinite = rememberInfiniteTransition(label = "nfc_spoof_animation")
    val scale by infinite.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse),
        label = "dolphin_scale",
    )

    var expanded by remember { mutableStateOf(false) }
    val pageFlippy = stringResource(R.string.audio_spoof_page_flippy)
    val pageWifi = stringResource(R.string.audio_spoof_page_wifi)
    val pageError = stringResource(R.string.audio_spoof_page_error)
    val pageVpn = stringResource(R.string.audio_spoof_page_vpn)
    val pagePick = stringResource(R.string.audio_spoof_page_pick)
    val urlCopiedMsg = stringResource(R.string.audio_spoof_url_copied)
    val pages = listOf(
        "flippy" to pageFlippy,
        "wifi" to pageWifi,
        "error" to pageError,
        "vpn" to pageVpn
    )

    val showFirstTimeInfo by viewModel.showFirstTimeInfo.collectAsState()

    if (showFirstTimeInfo) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFirstTimeInfo() },
            title = { Text(stringResource(R.string.audio_spoof_info_title), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(R.string.audio_spoof_info_text), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                com.droid.flippy.AccentButton(onClick = { viewModel.dismissFirstTimeInfo() }) {
                    Text(stringResource(R.string.got_it), color = accentColor)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("NFC Audio Spoof", color = Color.White) },
                navigationIcon = {
                    FlippyIconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), tint = Color.White)
                    }
                },
                actions = {
                    if (!isStarted) {
                        FlippyIconButton(onClick = { viewModel.startServer() }) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.audio_spoof_start), tint = accentColor)
                        }
                    } else {
                        FlippyIconButton(onClick = { viewModel.stopServer() }) {
                            Icon(Icons.Default.Stop, stringResource(R.string.audio_spoof_stop), tint = Color.Red)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isStarted && nfcReady) {
                        Image(
                            painter = painterResource(id = R.drawable.nfc_dolphin_emulation_51x64_transparent),
                            contentDescription = null,
                            modifier = Modifier
                                .size(width = 153.dp, height = 192.dp)
                                .graphicsLayer(scaleX = scale, scaleY = scale),
                            colorFilter = ColorFilter.tint(accentColor),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.audio_spoof_emulating),
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    } else {
                        if (!nfcReady) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(120.dp),
                                tint = accentColor
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.passport_bad2_46x49),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(width = 138.dp, height = 147.dp),
                                colorFilter = ColorFilter.tint(Color.Gray),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (!nfcReady) stringResource(R.string.audio_spoof_nfc_off) else stringResource(R.string.audio_spoof_ready_state),
                            color = if (!nfcReady) accentColor else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        if (!nfcReady) {
                            Text(
                                stringResource(R.string.nfc_prompt_enable),
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(spoofUrl, color = Color.Gray, fontSize = 12.sp)
                if (isStarted && serverIp != null) {
                    val clipboard = LocalClipboardManager.current
                    val context = LocalContext.current
                    FlippyIconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(spoofUrl))
                            Toast.makeText(context, urlCopiedMsg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.audio_spoof_copy),
                            tint = accentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))


            Text(stringResource(R.string.audio_spoof_choose_page), color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Card(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pages.find { it.first == pageType }?.second ?: pagePick,
                            modifier = Modifier.weight(1f),
                            color = Color.White
                        )
                        Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f).background(Color(0xFF1A1A1A))
                ) {
                    pages.forEach { (type, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = Color.White) },
                            onClick = {
                                viewModel.setPageType(type)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))


            val deviceCount by viewModel.deviceCount.collectAsState()
            val customAudios by viewModel.customAudioList.collectAsState()

            val audioPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                uri?.let { viewModel.addCustomAudio(it) }
            }

            Text(stringResource(R.string.audio_spoof_sound), color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.audio_spoof_connected, deviceCount), color = accentColor, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoundButton("", Icons.Default.Email, accentColor) { viewModel.sendCommand("PLAY:iphone_message.mp3") }
                SoundButton("", Icons.Default.Call, accentColor) { viewModel.sendCommand("PLAY:ios_call.mp3") }
            }

            customAudios.chunked(2).forEach { pair ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundButton("", null, accentColor, "🎵") { viewModel.sendCommand("PLAY:${pair[0]}") }
                    if (pair.size > 1) {
                        SoundButton("", null, accentColor, "🎵") { viewModel.sendCommand("PLAY:${pair[1]}") }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoundButton("", null, accentColor, "🐷") { viewModel.sendCommand("PLAY:vizg_svini.mp3") }
                SoundButton("", Icons.Default.Add, Color.Gray) {
                    audioPickerLauncher.launch("audio/*")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))


            var ttsText by remember { mutableStateOf("") }
            Text(stringResource(R.string.audio_spoof_tts), color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = ttsText,
                    onValueChange = { ttsText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.audio_spoof_tts_hint), fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = accentColor,
                        focusedIndicatorColor = accentColor,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                com.droid.flippy.AccentButton(
                    onClick = {
                        if (ttsText.isNotBlank()) {
                            viewModel.sendCommand("TTS:$ttsText")
                            ttsText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(stringResource(R.string.audio_spoof_say), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))


            Text(stringResource(R.string.audio_spoof_logs), color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        Text(
                            text = "> $log",
                            color = accentColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun RowScope.SoundButton(label: String, icon: ImageVector?, color: Color, emoji: String? = null, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.height(80.dp).weight(1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (emoji != null) {
                Text(emoji, fontSize = 32.sp)
            } else if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
            }
            if (label.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

