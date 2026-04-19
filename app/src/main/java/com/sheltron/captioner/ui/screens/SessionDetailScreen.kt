package com.sheltron.captioner.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sheltron.captioner.audio.PlaybackController
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.BoneMuted
import com.sheltron.captioner.ui.theme.InkRaised
import com.sheltron.captioner.ui.vm.CaptionerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionDetailScreen(
    vm: CaptionerViewModel,
    sessionId: Long,
    onBack: () -> Unit
) {
    val lines by vm.linesFor(sessionId).collectAsState(initial = emptyList())
    val session by vm.sessionFlow(sessionId).collectAsState(initial = null)
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    val audioFile = remember(sessionId) { vm.audioFileFor(sessionId) }
    val hasAudio = remember(sessionId, audioFile) { audioFile.exists() && audioFile.length() > 0 }

    val controller = remember(sessionId) { if (hasAudio) PlaybackController() else null }
    LaunchedEffect(controller, hasAudio) {
        if (controller != null && hasAudio) controller.prepare(audioFile)
    }
    DisposableEffect(controller) {
        onDispose { controller?.close() }
    }

    val playState = controller?.state?.collectAsState()?.value ?: PlaybackController.State.Idle
    val currentPositionMs = when (playState) {
        is PlaybackController.State.Playing -> playState.positionMs.toLong()
        is PlaybackController.State.Paused -> playState.positionMs.toLong()
        else -> -1L
    }
    val activeLineId = remember(currentPositionMs, lines) {
        if (currentPositionMs < 0 || lines.isEmpty()) -1L
        else {
            // Find the last line whose offsetMs <= currentPositionMs.
            var active = -1L
            for (l in lines) {
                if (l.offsetMs <= currentPositionMs) active = l.id else break
            }
            active
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                vm.buildTranscriptText(sessionId) { text ->
                    if (text.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, session?.title ?: "Transcript")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share transcript"))
                    }
                }
            }) {
                Icon(Icons.Outlined.Share, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = Accent)
            }
        }

        // Header
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text(
                session?.title ?: "Session",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            session?.let {
                Text(
                    formatMeta(it.startedAt, it.endedAt, lines.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = BoneMuted
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No captions captured.", color = BoneDim)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp, top = 8.dp,
                        bottom = if (controller != null) 96.dp else 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(lines, key = { it.id }) { line ->
                        val isActive = line.id == activeLineId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                formatOffset(line.offsetMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) Accent else BoneDim,
                                modifier = Modifier.padding(top = 4.dp, end = 12.dp)
                            )
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isActive) Accent else MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        if (controller != null) {
            MiniPlayer(state = playState, onToggle = { controller.togglePlayPause() },
                       onSeek = { controller.seekTo(it) })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the transcript and audio permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSession(sessionId)
                    onBack()
                }) { Text("Delete", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun MiniPlayer(
    state: PlaybackController.State,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val (position, duration, isPlaying) = when (state) {
        is PlaybackController.State.Playing -> Triple(state.positionMs, state.durationMs, true)
        is PlaybackController.State.Paused -> Triple(state.positionMs, state.durationMs, false)
        is PlaybackController.State.Prepared -> Triple(0, state.durationMs, false)
        else -> Triple(0, 0, false)
    }

    Surface(
        color = InkRaised,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    null,
                    tint = Accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                formatOffset(position.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = BoneMuted,
                modifier = Modifier.padding(end = 8.dp)
            )
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { if (duration > 0) onSeek((it * duration).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color(0xFF3A3A3A)
                ),
                modifier = Modifier.weight(1f)
            )
            Text(
                formatOffset(duration.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = BoneMuted,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun formatMeta(start: Long, end: Long?, lineCount: Int): String {
    val fmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    val startText = fmt.format(Date(start))
    val dur = if (end != null) {
        val s = (end - start) / 1000
        val m = s / 60
        val r = s % 60
        if (m > 0) "${m}m ${r}s" else "${r}s"
    } else "in progress"
    return "$startText · $dur · $lineCount lines"
}

private fun formatOffset(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
