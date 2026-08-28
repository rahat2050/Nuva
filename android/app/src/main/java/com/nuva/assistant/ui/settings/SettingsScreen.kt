package com.nuva.assistant.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.automation.QuickSettingsTileController
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.service.WakeWordService
import com.nuva.assistant.systemassistant.SystemAssistantController
import com.nuva.assistant.ui.theme.NuvaDivider
import com.nuva.assistant.ui.theme.NuvaGlassPanel
import com.nuva.assistant.ui.theme.NuvaScreenHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings: backend URL, Supabase connection, language, voice, confirmation
 * mode and system-assistant activation. NOTE: risky-action confirmation cannot
 * be turned off — only switched between "always" and "risky_only" (§11).
 */
class SettingsViewModel : ViewModel() {

    val preferences = NuvaContainer.preferences
    val signedIn = preferences.signedIn

    var message = mutableStateOf<String?>(null)
        private set

    var homeAssistantConfigured = mutableStateOf(NuvaContainer.homeAssistantConfig.isConfigured())
        private set

    fun setMessage(text: String?) {
        message.value = text
    }

    fun saveBaseUrl(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setBaseUrl(url)
            val healthy = NuvaContainer.syncManager.healthOk()
            message.value = if (healthy) "Backend reachable ✓" else "Saved — but /api/health did not answer"
        }
    }

    /** Explicit backend health check (requirement §31). */
    fun checkHealth() {
        CoroutineScope(Dispatchers.IO).launch {
            message.value = "Checking backend…"
            val healthy = NuvaContainer.syncManager.healthOk()
            message.value = if (healthy) {
                "Backend reachable ✓ (${preferences.baseUrlBlocking()})"
            } else {
                "Backend reachable hoy na — URL thik ache kina dekhun"
            }
        }
    }

    /** Speaks a sample sentence so the user can verify TTS (requirement §31). */
    fun testTts() {
        val tts = com.nuva.assistant.voice.TTSManager(NuvaContainer.appContext)
        val language = NuvaContainer.preferences.languageBlocking()
        val sample = when (language) {
            "en" -> "Hello, this is Nuva."
            else -> "Assalamu alaikum, ami Nuva — apnar voice assistant."
        }
        tts.speak(sample, if (language == "en") "en" else "banglish")
        message.value = "TTS test: \"$sample\""
    }

    fun saveSupabase(url: String, anonKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setSupabase(url, anonKey)
            message.value = "Supabase connection saved"
        }
    }

    fun saveHomeAssistant(url: String, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = NuvaContainer.homeAssistantConfig.save(url, token.takeIf { it.isNotBlank() })
            homeAssistantConfigured.value = NuvaContainer.homeAssistantConfig.isConfigured()
            message.value = result.fold(
                onSuccess = { "Home Assistant config encrypted and saved" },
                onFailure = { it.message ?: "Home Assistant config save failed" },
            )
        }
    }

    fun testHomeAssistant() {
        CoroutineScope(Dispatchers.IO).launch {
            message.value = "Checking Home Assistant…"
            message.value = when (val result = NuvaContainer.homeAssistantClient.health()) {
                is com.nuva.assistant.homeassistant.HomeAssistantClient.Result.Done -> "Home Assistant connected ✓"
                com.nuva.assistant.homeassistant.HomeAssistantClient.Result.NotConfigured -> "Home Assistant is not configured"
                is com.nuva.assistant.homeassistant.HomeAssistantClient.Result.Failed -> result.reason
                else -> "Home Assistant check failed"
            }
        }
    }

    fun clearHomeAssistant() {
        NuvaContainer.homeAssistantConfig.clear()
        homeAssistantConfigured.value = false
        message.value = "Home Assistant config removed"
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

    fun setWakeWord(enabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setWakeWordEnabled(enabled)
            if (enabled) {
                val started = WakeWordService.start(NuvaContainer.appContext)
                message.value = if (started) {
                    "Wake listener starting — keep its visible notification on."
                } else {
                    "Android wake listener start করতে দেয়নি; app foreground থেকে আবার চেষ্টা করুন।"
                }
            } else {
                WakeWordService.stop(NuvaContainer.appContext)
                message.value = "Wake word stopped."
            }
        }
    }

    fun restartWakeWord() {
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setWakeWordEnabled(true)
            WakeWordService.stop(NuvaContainer.appContext)
            delay(300)
            val started = WakeWordService.start(NuvaContainer.appContext)
            message.value = if (started) "Wake listener restarted." else "Wake listener restart failed."
        }
    }

    fun testVoiceSurface() {
        val started = WakeWordService.trigger(NuvaContainer.appContext)
        message.value = if (started) {
            "Voice surface test started — এখন একটি command বলুন।"
        } else {
            "Android voice surface start করতে দেয়নি।"
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onOpenSetup: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRefresh by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val preferences = viewModel.preferences
    val baseUrl by preferences.baseUrl.collectAsState(initial = com.nuva.assistant.core.constants.AppConstants.DEFAULT_BASE_URL)
    val supabaseUrl by preferences.supabaseUrl.collectAsState(initial = "")
    val language by preferences.language.collectAsState(initial = "auto")
    val voiceEnabled by preferences.voiceEnabled.collectAsState(initial = true)
    val confirmationAlways by preferences.confirmationAlways.collectAsState(initial = false)
    val wakeWordEnabled by preferences.wakeWordEnabled.collectAsState(initial = false)
    val wakeRuntimeStatus by WakeWordService.runtimeStatus.collectAsState()
    val directCall by preferences.directCall.collectAsState(initial = false)
    val signedIn by viewModel.signedIn.collectAsState(initial = false)
    val message by viewModel.message
    val homeAssistantConfigured by viewModel.homeAssistantConfigured

    var baseUrlDraft by remember(baseUrl) { mutableStateOf(baseUrl) }
    var supabaseDraft by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
    var anonKeyDraft by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var homeAssistantUrl by remember { mutableStateOf(NuvaContainer.homeAssistantConfig.savedBaseUrl()) }
    var homeAssistantToken by remember { mutableStateOf("") }

    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val overlayGranted = remember(permissionRefresh) { NuvaPermissions.canDrawOverlays(context) }
    val runtimeMissing = remember(permissionRefresh) { NuvaPermissions.missingWakeWordRuntimePermissions(context) }
    val notificationGranted = remember(permissionRefresh) { NuvaPermissions.hasNotifications(context) }
    val micGranted = remember(permissionRefresh) { NuvaPermissions.hasRecordAudio(context) }
    val nuvaIsDefaultAssistant = remember(permissionRefresh) {
        SystemAssistantController.isNuvaDefault(context)
    }

    val wakePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefresh++
        if (!NuvaPermissions.hasWakeWordRuntimePermissions(context)) {
            viewModel.setMessage("Microphone and notification permission are required for Hey Nuva.")
        } else if (!NuvaPermissions.canDrawOverlays(context)) {
            viewModel.setMessage("Enable “Display over other apps”, then return and switch Hey Nuva on.")
            NuvaPermissions.openOverlaySettings(context)
        } else {
            viewModel.setWakeWord(true)
        }
    }

    fun enableWakeWord() {
        val missing = NuvaPermissions.missingWakeWordRuntimePermissions(context)
        if (missing.isNotEmpty()) {
            wakePermissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!NuvaPermissions.canDrawOverlays(context)) {
            viewModel.setMessage("Enable “Display over other apps”, then return and switch Hey Nuva on.")
            NuvaPermissions.openOverlaySettings(context)
            return
        }
        viewModel.setWakeWord(true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NuvaScreenHeader(
            eyebrow = "CONTROL DECK",
            title = stringResource(R.string.nav_settings),
            subtitle = "Connections, privacy, voice এবং Android permissions",
        )

        OutlinedTextField(
            value = baseUrlDraft,
            onValueChange = { baseUrlDraft = it },
            label = { Text(stringResource(R.string.settings_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { viewModel.saveBaseUrl(baseUrlDraft) } }) { Text("Save") }
            OutlinedButton(onClick = { viewModel.checkHealth() }) {
                Text(stringResource(R.string.settings_health_check))
            }
        }

        NuvaDivider()

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

        NuvaDivider()

        Text("Home Assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            if (homeAssistantConfigured) "Configured securely ✓" else "Optional · HTTPS only · token stays encrypted on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = if (homeAssistantConfigured) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = homeAssistantUrl,
            onValueChange = { homeAssistantUrl = it },
            label = { Text("Home Assistant HTTPS URL") },
            placeholder = { Text("https://home.example.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = homeAssistantToken,
            onValueChange = { homeAssistantToken = it },
            label = { Text(if (homeAssistantConfigured) "New token (leave blank to keep saved token)" else "Long-lived access token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.saveHomeAssistant(homeAssistantUrl, homeAssistantToken)
                    homeAssistantToken = ""
                },
                enabled = homeAssistantUrl.isNotBlank(),
            ) { Text("Save encrypted") }
            OutlinedButton(onClick = { viewModel.testHomeAssistant() }, enabled = homeAssistantConfigured) { Text("Test") }
            if (homeAssistantConfigured) {
                TextButton(onClick = { viewModel.clearHomeAssistant() }) { Text("Remove") }
            }
        }

        NuvaDivider()

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto", "bn", "en", "banglish").forEach { option ->
                val selected = language == option
                OutlinedButton(
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
        ToggleRow(
            label = stringResource(R.string.settings_direct_call),
            checked = directCall,
            onChange = { scope.launch { preferences.setDirectCall(it) } },
        )
        OutlinedButton(onClick = { viewModel.testTts() }) {
            Text(stringResource(R.string.settings_tts_test))
        }

        NuvaDivider()

        Text(stringResource(R.string.settings_status_title), style = MaterialTheme.typography.titleMedium)
        val accessibilityRunning = remember(permissionRefresh) {
            com.nuva.assistant.accessibility.NuvaAccessibilityService.isRunning
        }
        val notificationListenerConnected = remember(permissionRefresh) {
            com.nuva.assistant.service.NuvaNotificationListener.isConnected
        }
        val contactsGranted = remember(permissionRefresh) { NuvaPermissions.hasContacts(context) }
        Text(
            buildString {
                append("Accessibility: ").append(if (accessibilityRunning) "✓ on" else "✗ off").append("  ·  ")
                append("Notification access: ").append(if (notificationListenerConnected) "✓" else "✗").append("  ·  ")
                append("Contacts: ").append(if (contactsGranted) "✓" else "✗")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (accessibilityRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSetup) { Text(stringResource(R.string.settings_open_setup)) }
            OutlinedButton(onClick = onOpenSupport) { Text(stringResource(R.string.settings_open_support)) }
            OutlinedButton(onClick = onOpenPrivacy) { Text(stringResource(R.string.settings_open_privacy)) }
        }

        NuvaDivider()

        Text("Hey NUVA & default assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            if (nuvaIsDefaultAssistant) {
                "Default digital assistant: NUVA ✓"
            } else {
                "Step 1: Android Default apps থেকে NUVA-কে digital assistant হিসেবে বেছে নিন।"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (nuvaIsDefaultAssistant) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        OutlinedButton(
            onClick = {
                val opened = SystemAssistantController.openAssistantPicker(context)
                viewModel.setMessage(
                    if (opened) "Digital assistant app খুলে NUVA select করুন, তারপর ফিরে আসুন।"
                    else "এই ফোনে default assistant settings খোলা যায়নি।",
                )
            },
        ) {
            Text(if (nuvaIsDefaultAssistant) "Check default assistant" else "Choose NUVA as default")
        }
        OutlinedButton(
            onClick = {
                val activity = context as? Activity
                if (activity == null) {
                    viewModel.setMessage("Quick Settings tile request needs the visible NUVA Activity.")
                } else {
                    QuickSettingsTileController.requestAdd(activity) { result ->
                        viewModel.setMessage(QuickSettingsTileController.message(result))
                    }
                }
            },
        ) {
            Text(stringResource(R.string.settings_add_quick_tile))
        }
        Text(
            "App icon long-press করলেও Talk to NUVA shortcut পাবেন। Tile/shortcut শুধু visible listening session খোলে।",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToggleRow(
            label = "Step 2: " + stringResource(R.string.settings_wake_word),
            checked = wakeWordEnabled,
            onChange = { enabled ->
                if (enabled) enableWakeWord() else viewModel.setWakeWord(false)
            },
        )
        Text(
            "Listener: ${wakeRuntimeStatus.state.name.lowercase().replace('_', ' ')} · ${wakeRuntimeStatus.detail}",
            style = MaterialTheme.typography.bodyMedium,
            color = when (wakeRuntimeStatus.state) {
                WakeWordService.RuntimeState.WAITING_FOR_WAKE,
                WakeWordService.RuntimeState.WAKE_DETECTED,
                WakeWordService.RuntimeState.LISTENING_FOR_COMMAND,
                WakeWordService.RuntimeState.PROCESSING -> MaterialTheme.colorScheme.secondary
                WakeWordService.RuntimeState.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            "Screen on থাকলে verified “Hey Nuva” NUVA খুলবে। Google/Gemini-এর screen-off low-power DSP hotword OEM/system-only; normal APK সেটি দখল করতে পারে না।",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Permissions: mic ${if (micGranted) "✓" else "missing"} · notification ${if (notificationGranted) "✓" else "missing"} · overlay ${if (overlayGranted) "✓" else "missing"}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (runtimeMissing.isEmpty() && overlayGranted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.restartWakeWord() },
                enabled = wakeWordEnabled && runtimeMissing.isEmpty() && overlayGranted,
            ) { Text("Restart listener") }
            OutlinedButton(
                onClick = { viewModel.testVoiceSurface() },
                enabled = runtimeMissing.isEmpty() && overlayGranted,
            ) { Text("Test voice") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!overlayGranted) {
                OutlinedButton(onClick = { NuvaPermissions.openOverlaySettings(context) }) {
                    Text("Overlay permission")
                }
            }
            OutlinedButton(onClick = { NuvaPermissions.openBatteryOptimizationSettings(context) }) {
                Text("Battery")
            }
            OutlinedButton(onClick = { NuvaPermissions.openAccessibilitySettings(context) }) {
                Text("Accessibility")
            }
        }

        message?.let { currentMessage ->
            NuvaGlassPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = MaterialTheme.colorScheme.secondary,
                contentPadding = 12.dp,
            ) {
                Text(
                    currentMessage,
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
    NuvaGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        contentPadding = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
