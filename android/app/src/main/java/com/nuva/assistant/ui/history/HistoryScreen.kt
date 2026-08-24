package com.nuva.assistant.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** Command history — the local audit trail (Room), newest first. */
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
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val history by viewModel.history.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.nav_history), style = MaterialTheme.typography.headlineMedium)
            if (history.isNotEmpty()) {
                TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
            }
        }

        if (history.isEmpty()) {
            Text(
                stringResource(R.string.no_commands_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.text, style = MaterialTheme.typography.bodyMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${row.intent} · ${row.risk} · ${row.status}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when (row.status) {
                                        "completed" -> MaterialTheme.colorScheme.secondary
                                        "failed", "rejected", "unsupported" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    formatTime(row.createdAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
