package com.nuva.assistant.ui.home

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.nuva.assistant.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * Voice-first home screen (blueprint §2.3): states
 * idle / listening / processing / executing / done / failed / confirm.
 */
class HomeViewModel : ViewModel() {

    val voice: VoiceController = VoiceController()
    val state = voice.state

    val recent = NuvaContainer.database
        .commandHistoryDao()
        .recent(20)
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Lazily, emptyList())

    val pending = MutableStateFlow<Pair<Long, CommandDecision>?>(null)

    fun startListening() = voice.startListening()

    fun onStateChanged(newState: VoiceController.State) {
        if (newState is VoiceController.State.AwaitingConfirmation) {
            pending.value = newState.pendingId to newState.decision
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

    val state by viewModel.state.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val recent by viewModel.recent.collectAsState()

    // Awaiting-confirmation states arrive via the voice pipeline — surface them
    // as the BLOCKING dialog (§3: confirmation can never be bypassed).
    androidx.compose.runtime.LaunchedEffect(state) {
        viewModel.onStateChanged(state)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NUVA", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(32.dp))

        // Big mic button
        Surface(
            onClick = {
                if (NuvaPermissions.hasRecordAudio(context)) {
                    viewModel.startListening()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            shape = CircleShape,
            color = if (state is VoiceController.State.Listening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(112.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎙️", style = MaterialTheme.typography.headlineMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        val status = when (val s = state) {
            VoiceController.State.Idle -> stringResource(R.string.how_can_i_help)
            VoiceController.State.Listening -> stringResource(R.string.listening)
            VoiceController.State.Processing -> stringResource(R.string.thinking)
            is VoiceController.State.Transcribed -> "\"${s.text}\""
            is VoiceController.State.AwaitingConfirmation -> stringResource(R.string.confirm_title)
            is VoiceController.State.Done -> s.speech
            is VoiceController.State.Failed -> s.speech
        }
        Text(
            status,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!NuvaAccessibilityService.isRunning) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.accessibility_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
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
                        ) {
                            Text(row.text, modifier = Modifier.weight(1f), maxLines = 2)
                            Text(
                                row.status,
                                color = when (row.status) {
                                    "completed" -> MaterialTheme.colorScheme.secondary
                                    "failed", "rejected" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // BLOCKING confirmation dialog (§3: confirmation can never be bypassed).
    val pendingPair = pending
    if (pendingPair != null) {
        val decision = pendingPair.second
        AlertDialog(
            onDismissRequest = { viewModel.reject() },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = {
                Column {
                    if (decision.action != null) {
                        Text("${decision.intent?.wireName} · risk: ${decision.risk.name.lowercase()}")
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(decision.speech.ifBlank { "Ei kaj ta korbo?" })
                    decision.reasons.firstOrNull()?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}
