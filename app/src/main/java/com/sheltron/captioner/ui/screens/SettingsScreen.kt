package com.sheltron.captioner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var apiKey by remember { mutableStateOf(store.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(store.model) }
    var selectedEngine by remember { mutableStateOf(store.engine) }
    var savedHint by remember { mutableStateOf(false) }

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
            modifier = Modifier.padding(horizontal = 16.dp),
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
                        "Which engine turns your voice into captions.",
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
                        "Anthropic API key",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Used only for task extraction. Stored encrypted on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            savedHint = false
                            store.apiKey = it
                        },
                        placeholder = { Text("sk-ant-…", color = BoneDim) },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    null, tint = BoneMuted
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = BoneDim,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = Accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Surface(
                color = InkRaised,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Extraction model",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Which model to use when extracting tasks from a session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    SettingsStore.Model.entries.forEach { model ->
                        ModelOption(
                            display = model.display,
                            selected = model == selectedModel,
                            onClick = {
                                selectedModel = model
                                store.model = model
                            }
                        )
                        if (model != SettingsStore.Model.entries.last()) Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "On-device (Gemini Nano): not wired yet — coming in a later build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BoneDim
                    )
                }
            }
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

@Composable
private fun ModelOption(display: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) InkElevated else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(Icons.Outlined.Check, null, tint = Accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}
