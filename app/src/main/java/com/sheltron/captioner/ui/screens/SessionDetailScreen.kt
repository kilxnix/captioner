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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.BoneMuted
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

        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No captions captured.", color = BoneDim)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lines, key = { it.id }) { line ->
                    Row {
                        Text(
                            formatOffset(line.offsetMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = BoneDim,
                            modifier = Modifier.padding(top = 4.dp, end = 12.dp)
                        )
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("This removes the transcript permanently.") },
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
