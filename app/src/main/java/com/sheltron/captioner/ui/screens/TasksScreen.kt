package com.sheltron.captioner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sheltron.captioner.data.db.Task
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.BoneMuted
import com.sheltron.captioner.ui.theme.InkRaised
import com.sheltron.captioner.ui.vm.CaptionerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(
    vm: CaptionerViewModel,
    onOpenSession: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val tasks by vm.tasks.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Tasks",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Extracted from your sessions. Persist across session deletes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BoneMuted
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(InkRaised, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.Checklist, null, tint = Accent) }
                    Spacer(Modifier.height(12.dp))
                    Text("No tasks yet.", color = MaterialTheme.colorScheme.onSurface,
                         style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open a session and tap \"Extract tasks\".",
                        color = BoneMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { vm.setTaskDone(task.id, !task.done) },
                        onOpenSource = {
                            task.sourceSessionId?.let(onOpenSession)
                        },
                        onDelete = { vm.deleteTask(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onToggle: () -> Unit,
    onOpenSource: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = InkRaised,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = task.done,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Accent,
                    uncheckedColor = BoneDim,
                    checkmarkColor = Color.Black
                )
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp, top = 10.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (task.done) BoneDim else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None
                )
                if (task.contextSnippet.isNotBlank()) {
                    Text(
                        "\"${task.contextSnippet}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PriorityDot(task.priority)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        task.priority.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = BoneDim
                    )
                    task.dueDate?.let { due ->
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Outlined.CalendarToday, null,
                            tint = BoneDim,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            formatDate(due),
                            style = MaterialTheme.typography.labelSmall,
                            color = BoneDim
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (task.sourceSessionId != null) {
                    IconButton(onClick = onOpenSource, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Launch, null,
                             tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, null,
                         tint = BoneDim, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun PriorityDot(priority: String) {
    val color = when (priority) {
        "high" -> Color(0xFFE57373)
        "low" -> Color(0xFF81C784)
        else -> Accent
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

private fun formatDate(ms: Long): String {
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    return fmt.format(Date(ms))
}
