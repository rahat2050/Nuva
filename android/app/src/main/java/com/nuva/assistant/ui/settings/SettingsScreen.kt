package com.nuva.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch

/**
 * Settings: backend URL, Supabase connection, language, voice, confirmation
 * mode. NOTE: risky-action confirmation cannot be turned off — only switched
 * between "always" and "risky_only" (§11).
 */
class SettingsViewModel : ViewModel() {

    val preferences = NuvaContainer.preferences
    val signedIn = preferences.signedIn

    var message = mutableStateOf<String?>(null)
        private set

    fun saveBaseUrl(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setBaseUrl(url)
            val healthy = NuvaContainer.syncManager.healthOk()
            message.value = if (healthy) "Backend reachable ✓" else "Saved — but /api/health did not answer"
        }
    }

    fun saveSupabase(url: String, anonKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setSupabase(url, anonKey)
            message.value = "Supabase connection saved"
        }
    }

    fun signIn(email: String, password: String) {
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = NuvaContainer.supabaseRepository.signIn(email, password)) {
                is com.nuva.assistant.supabase.SupabaseRepository.SignInResult.Success -> {
                    preferences.saveSession(result.session.accessToken, result.session.refreshToken)
                    message.value = "Signed in as ${result.session.user?.email ?: "user"}"
                }

                is com.nuva.assistant.supabase.SupabaseRepository.SignInResult.Failure ->
                    message.value = result.reason
            }
        }
    }

    fun signOut() {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.clearSession()
            message.value = "Signed out"
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val preferences = viewModel.preferences
    val baseUrl by preferences.baseUrl.collectAsState(initial = com.nuva.assistant.core.constants.AppConstants.DEFAULT_BASE_URL)
    val supabaseUrl by preferences.supabaseUrl.collectAsState(initial = "")
    val language by preferences.language.collectAsState(initial = "auto")
    val voiceEnabled by preferences.voiceEnabled.collectAsState(initial = true)
    val confirmationAlways by preferences.confirmationAlways.collectAsState(initial = false)
    val signedIn by viewModel.signedIn.collectAsState(initial = false)
    val message by viewModel.message

    var baseUrlDraft by remember(baseUrl) { mutableStateOf(baseUrl) }
    var supabaseDraft by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
    var anonKeyDraft by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val scope = remember { CoroutineScope(Dispatchers.Main) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = baseUrlDraft,
            onValueChange = { baseUrlDraft = it },
            label = { Text(stringResource(R.string.settings_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(onClick = { scope.launch { viewModel.saveBaseUrl(baseUrlDraft) } }) { Text("Save") }

        HorizontalDivider()

        OutlinedTextField(
            value = supabaseDraft,
            onValueChange = { supabaseDraft = it },
            label = { Text("Supabase URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = anonKeyDraft,
            onValueChange = { anonKeyDraft = it },
            label = { Text("Supabase anon key (public)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(onClick = { viewModel.saveSupabase(supabaseDraft, anonKeyDraft) }) { Text("Save connection") }

        if (signedIn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Signed in ✓", color = MaterialTheme.colorScheme.secondary)
                Button(onClick = { viewModel.signOut() }) { Text("Sign out") }
            }
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = { viewModel.signIn(email, password) }) { Text("Sign in") }
        }

        HorizontalDivider()

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto", "bn", "en", "banglish").forEach { option ->
                val selected = language == option
                androidx.compose.material3.OutlinedButton(
                    onClick = { scope.launch { preferences.setLanguage(option) } },
                    colors = if (selected) {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(if (selected) "● $option" else option)
                }
            }
        }

        ToggleRow(
            label = stringResource(R.string.settings_voice),
            checked = voiceEnabled,
            onChange = { scope.launch { preferences.setVoiceEnabled(it) } },
        )
        ToggleRow(
            label = stringResource(R.string.settings_confirmation) + " — always",
            checked = confirmationAlways,
            onChange = { scope.launch { preferences.setConfirmationAlways(it) } },
        )

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
        }

        Text(
            "Risk-medium/high actions always ask first. There is no way to disable that.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
