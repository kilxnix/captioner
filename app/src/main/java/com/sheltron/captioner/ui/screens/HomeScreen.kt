package com.sheltron.captioner.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sheltron.captioner.audio.RecorderService
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.BoneMuted
import com.sheltron.captioner.ui.theme.Divider
import com.sheltron.captioner.ui.theme.InkElevated
import com.sheltron.captioner.ui.theme.InkRaised
import com.sheltron.captioner.ui.vm.CaptionerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: CaptionerViewModel,
    onStart: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTasks: () -> Unit = {}
) {
    val sessions by vm.sessions.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val serviceState by vm.serviceState.collectAsState()
    val context = LocalContext.current

    var micGranted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    ) }

    var notifGranted by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    LaunchedEffect(Unit) { vm.refreshModelState() }

    val recording = serviceState is RecorderService.ServiceState.Recording
    val starting = serviceState is RecorderService.ServiceState.Starting

    val topInset = WindowInsets.statusBars.asPaddingValues()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = topInset.calculateTopPadding())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Cole's Log",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Local voice → text. Swipe left for tasks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BoneMuted
                )
            }
            androidx.compose.material3.IconButton(onClick = onOpenTasks) {
                Icon(Icons.Outlined.Checklist, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
            androidx.compose.material3.IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        // Primary action card
        Surface(
            color = InkRaised,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    !micGranted -> PermissionBlock(
                        icon = { Icon(Icons.Outlined.Mic, null, tint = Accent) },
                        title = "Microphone access",
                        body = "Cole's Log needs the mic to hear what to transcribe.",
                        cta = "Grant microphone",
                        onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                    Build.VERSION.SDK_INT >= 33 && !notifGranted -> PermissionBlock(
                        icon = { Icon(Icons.Outlined.Mic, null, tint = Accent) },
                        title = "Notifications",
                        body = "Required so Android keeps the recording service alive in the background.",
                        cta = "Grant notifications",
                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    )
                    modelState is CaptionerViewModel.ModelState.Unknown -> ModelBlock(
                        title = "Voice model needed",
                        body = "~130 MB one-time download. Runs fully offline afterward.",
                        cta = "Download model",
                        onClick = { vm.downloadModel() }
                    )
                    modelState is CaptionerViewModel.ModelState.Downloading -> {
                        val pct = (modelState as CaptionerViewModel.ModelState.Downloading).percent
                        ModelProgress(label = "Downloading model", percent = pct)
                    }
                    modelState is CaptionerViewModel.ModelState.Extracting -> {
                        ModelProgress(label = "Extracting model", percent = null)
                    }
                    modelState is CaptionerViewModel.ModelState.Failed -> ModelBlock(
                        title = "Download failed",
                        body = (modelState as CaptionerViewModel.ModelState.Failed).message,
                        cta = "Retry",
                        onClick = { vm.downloadModel() }
                    )
                    else -> RecordButton(
                        recording = recording,
                        starting = starting,
                        onClick = {
                            if (recording) vm.stopRecording()
                            else { vm.startRecording(); onStart() }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Battery optimization hint (non-blocking)
        if (modelState is CaptionerViewModel.ModelState.Ready) {
            BatteryHint()
            Spacer(Modifier.height(16.dp))
        }

        // Sessions list
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Sessions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${sessions.size}",
                style = MaterialTheme.typography.labelMedium,
                color = BoneDim
            )
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No sessions yet. Hit record.",
                    color = BoneDim,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { s ->
                    SessionRow(
                        title = s.title,
                        subtitle = formatRange(s.startedAt, s.endedAt),
                        onClick = { onOpenSession(s.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBlock(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    cta: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(InkElevated, CircleShape),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium,
             color = BoneMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text(cta, color = Color.Black, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ModelBlock(title: String, body: String, cta: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(InkElevated, CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Download, null, tint = Accent) }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium,
             color = BoneMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text(cta, color = Color.Black, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ModelProgress(label: String, percent: Int?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (percent != null) "$label · $percent%" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = BoneMuted
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, starting: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = if (recording) Accent else InkElevated,
                    shape = CircleShape
                )
                .clickable(enabled = !starting, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            when {
                starting -> CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                recording -> Icon(
                    Icons.Outlined.GraphicEq, null,
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp)
                )
                else -> Icon(
                    Icons.Outlined.FiberManualRecord, null,
                    tint = Accent,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                starting -> "Starting..."
                recording -> "Recording · tap to stop"
                else -> "Tap to record"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SessionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        color = InkRaised,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = BoneMuted
            )
        }
    }
}

@Composable
private fun BatteryHint() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    if (ignoring) return

    Surface(
        color = InkRaised,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .clickable {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Allow background use",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Tap to exempt Cole's Log from battery optimization so long sessions survive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BoneMuted
                )
            }
        }
    }
}

private fun formatRange(start: Long, end: Long?): String {
    val fmt = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
    val startText = fmt.format(Date(start))
    val duration = if (end != null) {
        val secs = (end - start) / 1000
        val m = secs / 60
        val s = secs % 60
        if (m > 0) " · ${m}m ${s}s" else " · ${s}s"
    } else " · in progress"
    return "$startText$duration"
}
