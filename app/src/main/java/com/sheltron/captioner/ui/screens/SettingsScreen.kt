package com.sheltron.captioner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sheltron.captioner.settings.SettingsStore
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.BoneMuted
import com.sheltron.captioner.ui.theme.InkElevated
import com.sheltron.captioner.ui.theme.InkRaised
import com.sheltron.captioner.ui.vm.CaptionerViewModel

@Composable
fun SettingsScreen(
    vm: CaptionerViewModel,
    onBack: () -> Unit
) {
    val store = vm.settings
    var selectedEngine by remember { mutableStateOf(store.engine) }
    var soundsOn by remember { mutableStateOf(store.recordingSoundsEnabled) }
    val gemmaState by vm.gemmaState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.size(4.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = InkRaised,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Transcription engine",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Cole's Log is fully offline. Pick which engine captures live captions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    SettingsStore.Engine.entries.forEach { eng ->
                        EngineOption(
                            display = eng.display,
                            note = eng.note,
                            selected = eng == selectedEngine,
                            onClick = {
                                selectedEngine = eng
                                store.engine = eng
                            }
                        )
                        if (eng != SettingsStore.Engine.entries.last()) Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Surface(
                color = InkRaised,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Task extraction model (Gemma 3 1B)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Runs fully on-device via MediaPipe — no network after the one-time download, no API key. ~550 MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    when (val s = gemmaState) {
                        is CaptionerViewModel.GemmaState.Ready -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Check, null, tint = Accent,
                                     modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Model ready.",
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        is CaptionerViewModel.GemmaState.Downloading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(10.dp))
                                Text("Downloading… ${s.percent}%",
                                     style = MaterialTheme.typography.bodyMedium,
                                     color = BoneMuted)
                            }
                        }
                        is CaptionerViewModel.GemmaState.Failed -> {
                            Text("Download failed: ${s.message}",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = BoneDim)
                            Spacer(Modifier.size(10.dp))
                            Button(
                                onClick = { vm.downloadGemma() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Icon(Icons.Outlined.Download, null, tint = Color.Black,
                                     modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Retry", color = Color.Black)
                            }
                        }
                        else -> {
                            Button(
                                onClick = { vm.downloadGemma() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Icon(Icons.Outlined.Download, null, tint = Color.Black,
                                     modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Download model (~550 MB)", color = Color.Black)
                            }
                        }
                    }
                }
            }

            Surface(
                color = InkRaised,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Recording sounds",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Beep when recording starts and stops.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BoneMuted
                        )
                    }
                    Switch(
                        checked = soundsOn,
                        onCheckedChange = {
                            soundsOn = it
                            store.recordingSoundsEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = BoneMuted,
                            uncheckedTrackColor = InkElevated
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EngineOption(display: String, note: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) InkElevated else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    display,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = BoneMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (selected) {
                Icon(Icons.Outlined.Check, null, tint = Accent,
                     modifier = Modifier.size(18.dp).padding(top = 2.dp))
            }
        }
    }
}
