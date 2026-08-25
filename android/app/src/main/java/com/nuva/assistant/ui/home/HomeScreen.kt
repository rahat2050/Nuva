package com.nuva.assistant.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.accessibility.NuvaAccessibilityService
import com.nuva.assistant.command.CommandDecision
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.ui.ConfirmationSummary
import com.nuva.assistant.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * Voice-first home screen: idle → listening → transcribed → processing →
 * (contact choice) → confirmation → executing → done/failed. Typed input uses
 * the exact same pipeline; recognition failure offers the text box directly.
 */
class HomeViewModel : ViewModel() {

    val voice: VoiceController = VoiceController()
    val state = voice.state

    val recent = NuvaContainer.database
        .commandHistoryDao()
        .recent(20)
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Lazily, emptyList())

    val pending = MutableStateFlow<Pair<Long, CommandDecision>?>(null)
    val contactChoice =
        MutableStateFlow<Pair<Long, List<com.nuva.assistant.contacts.ContactResolver.ContactMatch>>?>(null)

    fun startListening() = voice.startListening()

    fun submitTyped(text: String) = voice.submit(text)

    fun retry(text: String) = voice.submit(text)

    fun onStateChanged(newState: VoiceController.State) {
        when (newState) {
            is VoiceController.State.AwaitingConfirmation ->
                pending.value = newState.pendingId to newState.decision

            is VoiceController.State.AwaitingContactChoice ->
                contactChoice.value = newState.pendingId to newState.matches

            else -> Unit
        }
    }

    fun confirm() {
        val pendingPair = pending.value ?: return
        pending.value = null
        voice.confirm(pendingPair.first)
    }

    fun reject() {
        val pendingPair = pending.value ?: return
        pending.value = null
        voice.reject(pendingPair.first)
    }

    fun chooseContact(match: com.nuva.assistant.contacts.ContactResolver.ContactMatch) {
        val choice = contactChoice.value ?: return
        contactChoice.value = null
        voice.chooseContact(choice.first, match)
    }

    fun cancelContactChoice() {
        val choice = contactChoice.value ?: return
        contactChoice.value = null
        voice.reject(choice.first)
    }

    override fun onCleared() {
        voice.destroy()
        super.onCleared()
    }
}

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startListening()
    }

    val state by viewModel.state.collectAsState(initial = VoiceController.State.Idle)
    val pending by viewModel.pending.collectAsState()
    val contactChoice by viewModel.contactChoice.collectAsState()
    val recent by viewModel.recent.collectAsState()

    var typedCommand by remember { mutableStateOf("") }
    var showAccessibilityGuide by remember { mutableStateOf(false) }

    LaunchedEffect(state) { viewModel.onStateChanged(state) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NUVA", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))

        // Big mic button + processing spinner.
        Surface(
            onClick = {
                if (NuvaPermissions.hasRecordAudio(context)) {
                    viewModel.startListening()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            shape = CircleShape,
            color = when (state) {
                is VoiceController.State.Listening -> MaterialTheme.colorScheme.error
                is VoiceController.State.Processing -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(112.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state is VoiceController.State.Processing) {
                    CircularProgressIndicator(modifier = Modifier.size(44.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("🎙️", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        val status = when (val s = state) {
            VoiceController.State.Idle -> stringResource(R.string.how_can_i_help)
            VoiceController.State.Listening -> stringResource(R.string.listening)
            VoiceController.State.Processing -> stringResource(R.string.thinking)
            is VoiceController.State.Transcribed -> "“${s.text}”"
            is VoiceController.State.AwaitingConfirmation -> stringResource(R.string.confirm_title)
            is VoiceController.State.AwaitingContactChoice -> stringResource(R.string.contact_choice_title)
            is VoiceController.State.Done -> s.speech
            is VoiceController.State.Failed -> s.speech
        }
        Text(
            status,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // Voice failed → the typed fallback is the primary affordance (req §2).
        if (state is VoiceController.State.Failed && (state as VoiceController.State.Failed).fromVoice) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.voice_failed_typed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Typed command input — always available, same pipeline as voice.
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = typedCommand,
                onValueChange = { typedCommand = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.typed_hint)) },
                singleLine = true,
            )
            Button(
                onClick = {
                    val text = typedCommand.trim()
                    if (text.isNotEmpty()) {
                        typedCommand = ""
                        viewModel.submitTyped(text)
                    }
                },
                enabled = typedCommand.isNotBlank() && state !is VoiceController.State.Processing,
            ) { Text(stringResource(R.string.send)) }
        }

        // Accessibility setup guide (req §13).
        if (!NuvaAccessibilityService.isRunning) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), onClick = { showAccessibilityGuide = true }) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.accessibility_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.accessibility_setup_link),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (state is VoiceController.State.Done && (state as VoiceController.State.Done).screenText != null) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    (state as VoiceController.State.Done).screenText.orEmpty(),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.recent_commands), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (recent.isEmpty()) {
            Text(
                stringResource(R.string.no_commands_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.text, maxLines = 2)
                                if (row.status in listOf("failed", "blocked", "unsupported") && !row.error.isNullOrBlank()) {
                                    Text(
                                        row.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Text(
                                row.status,
                                color = when (row.status) {
                                    "completed" -> MaterialTheme.colorScheme.secondary
                                    "failed", "rejected", "blocked" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (row.status in listOf("failed", "blocked", "unsupported")) {
                                Spacer(Modifier.width(6.dp))
                                TextButton(onClick = { viewModel.retry(row.text) }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // BLOCKING confirmation dialog — target, content, app and risk in Bangla.
    val pendingPair = pending
    if (pendingPair != null) {
        val decision = pendingPair.second
        val summary = decision.action?.let { ConfirmationSummary.build(it, decision.risk) }
        AlertDialog(
            onDismissRequest = { viewModel.reject() },
            title = { Text(summary?.title ?: stringResource(R.string.confirm_title)) },
            text = {
                Column {
                    if (summary != null) {
                        summary.lines.forEach { line ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    line.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(72.dp),
                                )
                                Text(line.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(summary.detail, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(summary.riskLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(decision.speech.ifBlank { stringResource(R.string.confirm_generic) })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirm() }) { Text(stringResource(R.string.confirm_yes)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.reject() }) { Text(stringResource(R.string.confirm_no)) }
            },
        )
    }

    // Multi-match contact selection — NUVA never guesses (req §16).
    val choice = contactChoice
    if (choice != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelContactChoice() },
            title = { Text(stringResource(R.string.contact_choice_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.contact_choice_hint))
                    Spacer(Modifier.height(8.dp))
                    choice.second.forEach { match ->
                        TextButton(
                            onClick = { viewModel.chooseContact(match) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(match.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(match.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { viewModel.cancelContactChoice() }) {
                    Text(stringResource(R.string.confirm_no))
                }
            },
        )
    }

    // Accessibility step-by-step setup guide (req §13).
    if (showAccessibilityGuide) {
        AlertDialog(
            onDismissRequest = { showAccessibilityGuide = false },
            title = { Text(stringResource(R.string.accessibility_guide_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.accessibility_guide_step1))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.accessibility_guide_step2))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.accessibility_guide_step3))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.accessibility_guide_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAccessibilityGuide = false
                    NuvaPermissions.openAccessibilitySettings(context)
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAccessibilityGuide = false }) {
                    Text(stringResource(R.string.later))
                }
            },
        )
    }
}
