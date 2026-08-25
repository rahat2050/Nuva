package com.nuva.assistant.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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

/**
 * Memory screen (§17): local-first preferences. Credential-like keys are
 * refused client-side too — the memory store is not a password vault.
 */
class MemoryViewModel : ViewModel() {

    val memories = NuvaContainer.memory
        .all
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Lazily, emptyList())

    /** Voice-captured notes & to-dos (v1.1) — local only. */
    val notes = NuvaContainer.database.noteDao()
        .byKind("note")
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Lazily, emptyList())

    val todos = NuvaContainer.database.noteDao()
        .byKind("todo")
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Lazily, emptyList())

    var message = mutableStateOf<String?>(null)
        private set

    fun save(key: String, value: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = NuvaContainer.memory.remember(key, value)
            message.value = result.fold(
                onSuccess = { "Saved: $key" },
                onFailure = { "NUVA does not store credentials in memory" },
            )
        }
    }

    fun delete(key: String) {
        CoroutineScope(Dispatchers.IO).launch {
            NuvaContainer.memory.forget(key)
        }
    }

    fun syncNow() {
        CoroutineScope(Dispatchers.IO).launch {
            val report = NuvaContainer.syncManager.syncAll()
            message.value = "Synced — pushed ${report.pushedMemories}, pulled ${report.pulledMemories}"
        }
    }

    fun toggleTodo(id: Long, done: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            NuvaContainer.database.noteDao().setDone(id, done)
        }
    }

    fun deleteNote(id: Long) {
        CoroutineScope(Dispatchers.IO).launch {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.nav_memory), style = MaterialTheme.typography.headlineMedium)

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { if (key.isNotBlank() && value.isNotBlank()) viewModel.save(key, value) }) {
                Text("Save")
            }
            TextButton(onClick = { viewModel.syncNow() }) { Text("Sync now") }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }

        if (todos.isNotEmpty()) {
            Text(stringResource(R.string.todos_title), style = MaterialTheme.typography.titleMedium)
        }
        todos.forEach { todo ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (todo.done) "✓ " else "○ ") + todo.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.toggleTodo(todo.id, !todo.done) }) {
                        Text(if (todo.done) stringResource(R.string.undo) else stringResource(R.string.done))
                    }
                }
            }
        }

        if (notes.isNotEmpty()) {
            Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleMedium)
        }
        notes.forEach { note ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(note.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.deleteNote(note.id) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }

        Text(stringResource(R.string.memories_title), style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories, key = { it.id }) { memory ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(memory.key, style = MaterialTheme.typography.titleMedium)
                            Text(memory.value, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { viewModel.delete(memory.key) }) { Text("Forget") }
                    }
                }
            }
        }
    }
}
