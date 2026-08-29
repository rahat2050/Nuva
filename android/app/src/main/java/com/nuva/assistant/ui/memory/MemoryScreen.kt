package com.nuva.assistant.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.security.SecurityPolicy
import com.nuva.assistant.core.security.SensitiveAppPolicy
import com.nuva.assistant.ui.theme.NuvaGlassPanel
import com.nuva.assistant.ui.theme.NuvaPrimaryAction
import com.nuva.assistant.ui.theme.NuvaScreenHeader
import com.nuva.assistant.ui.theme.NuvaStatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Memory screen (§17): local-first preferences. Credential-like keys are
 * refused client-side too — the memory store is not a password vault.
 */
class MemoryViewModel : ViewModel() {

    val memories = NuvaContainer.memory
        .all
        .map { rows ->
            rows.filter { row ->
                SecurityPolicy.isMemoryKeyAllowed(row.key) &&
                    !SensitiveAppPolicy.mentionsCredentials(row.value)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Voice-captured notes & to-dos (v1.1) — local only. */
    val notes = NuvaContainer.database.noteDao()
        .byKind("note")
        .map { rows -> rows.filterNot { SensitiveAppPolicy.mentionsCredentials(it.content) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todos = NuvaContainer.database.noteDao()
        .byKind("todo")
        .map { rows -> rows.filterNot { SensitiveAppPolicy.mentionsCredentials(it.content) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var message = mutableStateOf<String?>(null)
        private set

    fun save(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = NuvaContainer.memory.remember(key, value)
            message.value = result.fold(
                onSuccess = { "Saved: $key" },
                onFailure = { "NUVA does not store credentials in memory" },
            )
        }
    }

    fun delete(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NuvaContainer.memory.forget(key)
        }
    }

    fun syncNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val report = NuvaContainer.syncManager.syncAll()
            message.value = "Synced — pushed ${report.pushedMemories}, pulled ${report.pulledMemories}"
        }
    }

    fun toggleTodo(id: Long, done: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            NuvaContainer.database.noteDao().setDone(id, done)
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            NuvaContainer.database.noteDao().delete(id)
        }
    }
}

@Composable
fun MemoryScreen(viewModel: MemoryViewModel = viewModel()) {
    val memories by viewModel.memories.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val message by viewModel.message
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var pendingDeletion by remember { mutableStateOf<PendingMemoryDeletion?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            NuvaScreenHeader(
                eyebrow = "LOCAL-FIRST KNOWLEDGE",
                title = stringResource(R.string.nav_memory),
                subtitle = "Notes, to-dos এবং আপনার অনুমোদিত preferences",
            )
        }

        item {
            NuvaGlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("key — e.g. preferred_language") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("value") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NuvaPrimaryAction(
                            onClick = {
                                if (key.isNotBlank() && value.isNotBlank()) {
                                    val submittedValue = value
                                    value = ""
                                    viewModel.save(key, submittedValue)
                                }
                            },
                            enabled = key.isNotBlank() && value.isNotBlank(),
                        ) { Text("Save", color = Color.White) }
                        TextButton(onClick = { viewModel.syncNow() }) { Text("Sync now") }
                    }
                }
            }
        }

        message?.let { currentMessage ->
            item {
                NuvaGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.secondary,
                    contentPadding = 12.dp,
                ) {
                    Text(
                        currentMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        if (todos.isNotEmpty()) {
            item { MemorySectionTitle(stringResource(R.string.todos_title), todos.size) }
            items(todos, key = { "todo-${it.id}" }) { todo ->
                NuvaGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = if (todo.done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    contentPadding = 12.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (todo.done) "✓ " else "○ ") + todo.content,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.toggleTodo(todo.id, !todo.done) }) {
                            Text(if (todo.done) stringResource(R.string.undo) else stringResource(R.string.done))
                        }
                    }
                }
            }
        }

        if (notes.isNotEmpty()) {
            item { MemorySectionTitle(stringResource(R.string.notes_title), notes.size) }
            items(notes, key = { "note-${it.id}" }) { note ->
                NuvaGlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.tertiary,
                    contentPadding = 12.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(note.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { pendingDeletion = PendingMemoryDeletion.Note(note.id, note.content) },
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }

        item { MemorySectionTitle(stringResource(R.string.memories_title), memories.size) }
        if (memories.isEmpty()) {
            item {
                NuvaGlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Text("No saved preference yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(memories, key = { "memory-${it.id}" }) { memory ->
                NuvaGlassPanel(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(memory.key, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(memory.value, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(
                            onClick = { pendingDeletion = PendingMemoryDeletion.Preference(memory.key) },
                        ) { Text("Forget") }
                    }
                }
            }
        }
    }

    pendingDeletion?.let { deletion ->
        val target = when (deletion) {
            is PendingMemoryDeletion.Note -> "Note: ${deletion.preview.take(80)}"
            is PendingMemoryDeletion.Preference -> "Preference: ${deletion.key}"
        }
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete local data?") },
            text = { Text("$target permanently মুছে যাবে। এই কাজ undo করা যাবে না।") },
            confirmButton = {
                Button(
                    onClick = {
                        when (deletion) {
                            is PendingMemoryDeletion.Note -> viewModel.deleteNote(deletion.id)
                            is PendingMemoryDeletion.Preference -> viewModel.delete(deletion.key)
                        }
                        pendingDeletion = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.confirm_no))
                }
            },
        )
    }
}

private sealed interface PendingMemoryDeletion {
    data class Note(val id: Long, val preview: String) : PendingMemoryDeletion
    data class Preference(val key: String) : PendingMemoryDeletion
}

@Composable
private fun MemorySectionTitle(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        NuvaStatusChip(label = count.toString(), color = MaterialTheme.colorScheme.primary)
    }
}
