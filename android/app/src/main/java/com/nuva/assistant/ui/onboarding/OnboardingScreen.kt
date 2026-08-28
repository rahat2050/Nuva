package com.nuva.assistant.ui.onboarding

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.accessibility.NuvaAccessibilityService
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.service.NuvaNotificationListener
import com.nuva.assistant.ui.theme.NuvaGlassPanel
import com.nuva.assistant.ui.theme.NuvaPrimaryAction
import com.nuva.assistant.ui.theme.NuvaScreenHeader
import com.nuva.assistant.ui.theme.NuvaStatusChip
import kotlinx.coroutines.launch

/**
 * Permission onboarding (v1.1, requirements §28–§30):
 *  * every permission is explained in Bangla BEFORE it is requested;
 *  * each one is requested in context, by its own button — never a blanket
 *    "allow everything";
 *  * a denied permission never blocks the app — the card turns into help text
 *    and the feature simply stays unavailable until granted;
 *  * accessibility + notification access only open system settings, because
 *    Android does not allow apps to request them via dialogs.
 */
class OnboardingViewModel : ViewModel() {
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            NuvaContainer.preferences.setOnboardingDone(true)
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = viewModel(),
    onFinished: () -> Unit,
    onShowFeatures: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }

    // Re-check special access when the user comes back from system settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        val registry = lifecycleOwner.lifecycle
        registry.addObserver(observer)
        onDispose { registry.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val micGranted = remember(refresh) { NuvaPermissions.hasRecordAudio(context) }
    val notificationsGranted = remember(refresh) { NuvaPermissions.hasNotifications(context) }
    val accessibilityGranted = remember(refresh) { NuvaAccessibilityService.isRunning }
    val contactsGranted = remember(refresh) { NuvaPermissions.hasContacts(context) }
    val calendarGranted = remember(refresh) { NuvaPermissions.hasReadCalendar(context) }
    val smsGranted = remember(refresh) { NuvaPermissions.hasSendSms(context) }
    val notificationAccessGranted = remember(refresh) { NuvaNotificationListener.isConnected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvaScreenHeader(
            eyebrow = "PRIVATE BY DESIGN",
            title = stringResource(R.string.onboarding_title),
            subtitle = stringResource(R.string.onboarding_intro),
        )
        NuvaGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.secondary,
            contentPadding = 14.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("NUVA 3D", style = MaterialTheme.typography.titleLarge)
                    Text("Permission control stays yours", style = MaterialTheme.typography.bodySmall)
                }
                NuvaStatusChip("SECURE SETUP", MaterialTheme.colorScheme.secondary)
            }
        }

        PermissionCard(
            title = stringResource(R.string.perm_mic_title),
            description = stringResource(R.string.perm_mic_desc),
            granted = micGranted,
            onGrant = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            description = stringResource(R.string.perm_notifications_desc),
            granted = notificationsGranted,
            onGrant = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            optional = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_accessibility_title),
            description = stringResource(R.string.perm_accessibility_desc),
            granted = accessibilityGranted,
            onGrant = { NuvaPermissions.openAccessibilitySettings(context) },
            optional = true,
            isSpecialAccess = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_contacts_title),
            description = stringResource(R.string.perm_contacts_desc),
            granted = contactsGranted,
            onGrant = { launcher.launch(Manifest.permission.READ_CONTACTS) },
            optional = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_calendar_title),
            description = stringResource(R.string.perm_calendar_desc),
            granted = calendarGranted,
            onGrant = { launcher.launch(Manifest.permission.READ_CALENDAR) },
            optional = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_sms_title),
            description = stringResource(R.string.perm_sms_desc),
            granted = smsGranted,
            onGrant = { launcher.launch(Manifest.permission.SEND_SMS) },
            optional = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_notif_listener_title),
            description = stringResource(R.string.perm_notif_listener_desc),
            granted = notificationAccessGranted,
            onGrant = { NuvaNotificationListener.openAccessSettings(context) },
            optional = true,
            isSpecialAccess = true,
        )

        Spacer(Modifier.height(4.dp))
        NuvaGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.error,
            contentPadding = 13.dp,
        ) {
            Text(
                stringResource(R.string.onboarding_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvaPrimaryAction(
                onClick = { viewModel.finish(onFinished) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.onboarding_done), color = Color.White)
            }
            OutlinedButton(onClick = onShowFeatures) {
                Text(stringResource(R.string.onboarding_features))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    optional: Boolean = false,
    isSpecialAccess: Boolean = false,
) {
    val accent = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    NuvaGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                NuvaStatusChip(
                    label = when {
                        granted -> stringResource(R.string.perm_granted)
                        optional -> stringResource(R.string.perm_optional)
                        else -> stringResource(R.string.perm_required)
                    },
                    color = accent,
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                OutlinedButton(onClick = onGrant) {
                    Text(
                        if (isSpecialAccess) stringResource(R.string.perm_open_settings)
                        else stringResource(R.string.perm_allow),
                    )
                }
            }
        }
    }
}
