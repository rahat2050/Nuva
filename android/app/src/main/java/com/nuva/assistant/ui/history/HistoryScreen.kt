package com.nuva.assistant.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.core.NuvaContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Command history — the local audit trail (Room), newest first. Each row
 * shows status + failure reason, and failed/unsupported commands offer a
 * one-tap retry through the same validated pipeline (requirement §5).
 */
class HistoryViewModel : ViewModel() {

    val history = NuvaContainer.database
        .commandHistoryDao()
        .recent(200)
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Lazily, emptyList())

    fun clear() {
        CoroutineScope(Dispatchers.IO).launch {
            NuvaContainer.database.commandHistoryDao().clear()
        }
    }

    /** Re-runs a past command through the normal pipeline (validation + gates). */
    fun retry(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            NuvaContainer.commandExecutor.process(text)
        }
    }
}

private val STATUS_FILTERS = listOf("all", "completed", "failed", "pending")

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val history by viewModel.history.collectAsState()
    var filter by remember { mutableStateOf("all") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.nav_history), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { viewModel.clear() }) { Text(stringResource(R.string.clear)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STATUS_FILTERS.forEach { status ->
                FilterChip(
                    selected = filter == status,
                    onClick = { filter = status },
                    label = {
                        Text(
                            when (status) {
                                "all" -> stringResource(R.string.filter_all)
                                "completed" -> stringResource(R.string.filter_completed)
                                "failed" -> stringResource(R.string.filter_failed)
                                else -> stringResource(R.string.filter_pending)
                            },
                        )
                    },
                )
            }
        }

        Spacer(Modifier.padding(6.dp))

        val rows = when (filter) {
            "completed" -> history.filter { it.status == "completed" }
            "failed" -> history.filter { it.status in listOf("failed", "blocked", "unsupported") }
            "pending" -> history.filter { it.status.startsWith("pending") || it.status == "executing" }
            else -> history
        }

        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.no_commands_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.text, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.padding(3.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        "${row.intent} · ${statusLabel(row.status)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (row.status) {
                                            "completed" -> MaterialTheme.colorScheme.secondary
                                            "failed", "rejected", "blocked" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    row.error?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            "${stringResource(R.string.failure_reason)}: $it",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                Text(
                                    SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(row.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (row.status in listOf("failed", "blocked", "unsupported")) {
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
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "completed" -> stringResource(R.string.status_completed)
    "failed" -> stringResource(R.string.status_failed)
    "rejected" -> stringResource(R.string.status_rejected)
    "blocked" -> stringResource(R.string.status_blocked)
    "unsupported" -> stringResource(R.string.status_unsupported)
    "executing" -> stringResource(R.string.status_executing)
    "pending_confirmation" -> stringResource(R.string.status_pending)
    "pending_choice" -> stringResource(R.string.status_choice)
    else -> status
}
