package com.nuva.assistant.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.ui.theme.NuvaGlassPanel
import com.nuva.assistant.ui.theme.NuvaScreenHeader
import com.nuva.assistant.ui.theme.NuvaStatusChip
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) {
            NuvaContainer.database.commandHistoryDao().clear()
        }
    }

    /** Re-runs a past command through the normal pipeline (validation + gates). */
    fun retry(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NuvaContainer.commandExecutor.process(text)
        }
    }
}

private val STATUS_FILTERS = listOf("all", "completed", "failed", "pending")

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val history by viewModel.history.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val rows = when (filter) {
        "completed" -> history.filter { it.status == "completed" }
        "failed" -> history.filter { it.status in listOf("failed", "blocked", "unsupported") }
        "pending" -> history.filter { it.status.startsWith("pending") || it.status == "executing" }
        else -> history
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                NuvaScreenHeader(
                    eyebrow = "LOCAL AUDIT TRAIL",
                    title = stringResource(R.string.nav_history),
                    subtitle = "প্রতিটি command-এর result ও security status",
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { if (history.isNotEmpty()) showClearConfirmation = true },
                    enabled = history.isNotEmpty(),
                ) { Text(stringResource(R.string.clear)) }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
        }

        if (rows.isEmpty()) {
            item {
                NuvaGlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.no_commands_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(rows, key = { it.id }) { row ->
                val statusColor = when (row.status) {
                    "completed" -> MaterialTheme.colorScheme.secondary
                    "failed", "rejected", "blocked" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                NuvaGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = statusColor,
                    contentPadding = 14.dp,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                row.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            NuvaStatusChip(
                                label = statusLabel(row.status).uppercase(),
                                color = statusColor,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.intent,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                row.error?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "${stringResource(R.string.failure_reason)}: $it",
                                        style = MaterialTheme.typography.bodySmall,
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

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear command history?") },
            text = { Text("এই ফোনের ${history.size}টি history entry permanently মুছে যাবে। এই কাজ undo করা যাবে না।") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmation = false
                        viewModel.clear()
                    },
                ) { Text("Clear history") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.confirm_no))
                }
            },
        )
    }
}

@Composable
private fun statusLabel(status: String): String {
    // Canonical display set (Phase 20); internal Room statuses stay unchanged.
    return when (com.nuva.assistant.command.HistoryStatus.display(status)) {
        "SUCCESS" -> stringResource(R.string.status_completed)
        "FAILED" -> stringResource(R.string.status_failed)
        "CANCELLED" -> stringResource(R.string.status_rejected)
        "UNSUPPORTED" -> stringResource(R.string.status_unsupported)
        "BLOCKED" -> stringResource(R.string.status_blocked)
        else -> stringResource(R.string.status_pending)
    }
}
