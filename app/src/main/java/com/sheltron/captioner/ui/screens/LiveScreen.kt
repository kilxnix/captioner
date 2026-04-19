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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sheltron.captioner.audio.RecorderService
import com.sheltron.captioner.ui.theme.Accent
import com.sheltron.captioner.ui.theme.BoneDim
import com.sheltron.captioner.ui.theme.BoneMuted
import com.sheltron.captioner.ui.vm.CaptionerViewModel
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    vm: CaptionerViewModel,
    onBack: () -> Unit
) {
    val live by vm.live.collectAsState()
    val svcState by vm.serviceState.collectAsState()
    val listState = rememberLazyListState()

    val startedAt = (svcState as? RecorderService.ServiceState.Recording)?.startedAt

    var elapsed by remember { mutableStateOf("00:00") }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            elapsed = "%02d:%02d".format(secs / 60, secs % 60)
            delay(500)
        }
    }

    // Auto-scroll to newest on each final
    LaunchedEffect(live.finals.size) {
        if (live.finals.isNotEmpty()) {
            listState.animateScrollToItem((live.finals.size - 1).coerceAtLeast(0))
        }
    }

    var hasRecorded by remember { mutableStateOf(false) }
    var navigated by remember { mutableStateOf(false) }
    fun goBackOnce() {
        if (!navigated) {
            navigated = true
            onBack()
        }
    }

    LaunchedEffect(svcState) {
        when (svcState) {
            is RecorderService.ServiceState.Starting,
            is RecorderService.ServiceState.Recording -> hasRecorded = true
            is RecorderService.ServiceState.Idle,
            is RecorderService.ServiceState.Error -> {
                // Only auto-pop after we've actually started — otherwise we bounce back
                // immediately on initial composition before the service transitions to Starting.
                if (hasRecorded) goBackOnce()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Top bar: back + elapsed + REC dot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hasRecorded) vm.stopRecording() else goBackOnce()
            }) {
                Icon(Icons.Outlined.ArrowBack, null,
                     tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Accent, CircleShape)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                elapsed,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp)) // visual balance for back button
        }

        // Captions
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (live.finals.isEmpty() && live.partial.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Listening…",
                        style = MaterialTheme.typography.titleMedium,
                        color = BoneMuted
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Speak naturally. Captions appear below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneDim
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 24.dp, end = 24.dp, top = 16.dp, bottom = 180.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(live.finals.size) { i ->
                        Text(
                            live.finals[i],
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    if (live.partial.isNotBlank()) {
                        item {
                            Text(
                                live.partial,
                                style = MaterialTheme.typography.bodyLarge,
                                color = BoneDim
                            )
                        }
                    }
                }
            }
        }

        // Bottom stop button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp, top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(Accent, CircleShape)
                    .clickable { vm.stopRecording() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Stop, null,
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
