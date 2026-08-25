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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuva.assistant.R
import com.nuva.assistant.accessibility.NuvaAccessibilityService
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.service.NuvaNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        CoroutineScope(Dispatchers.IO).launch {
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
    val scope = rememberCoroutineScope()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionCard(
            title = stringResource(R.string.perm_mic_title),
            description = stringResource(R.string.perm_mic_desc),
            granted = NuvaPermissions.hasRecordAudio(context),
            onGrant = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            description = stringResource(R.string.perm_notifications_desc),
            granted = NuvaPermissions.hasNotifications(context),
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
            granted = NuvaAccessibilityService.isRunning,
            onGrant = { NuvaPermissions.openAccessibilitySettings(context) },
            optional = true,
            isSpecialAccess = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_contacts_title),
            description = stringResource(R.string.perm_contacts_desc),
            granted = NuvaPermissions.hasContacts(context),
            onGrant = { launcher.launch(Manifest.permission.READ_CONTACTS) },
            optional = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_sms_title),
            description = stringResource(R.string.perm_sms_desc),
            granted = NuvaPermissions.hasSendSms(context),
            onGrant = { launcher.launch(Manifest.permission.SEND_SMS) },
            optional = true,
        )

        PermissionCard(
            title = stringResource(R.string.perm_notif_listener_title),
            description = stringResource(R.string.perm_notif_listener_desc),
            granted = NuvaNotificationListener.isConnected,
            onGrant = { NuvaNotificationListener.openAccessSettings(context) },
            optional = true,
            isSpecialAccess = true,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.onboarding_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { viewModel.finish(onFinished) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_done))
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        granted -> stringResource(R.string.perm_granted)
                        optional -> stringResource(R.string.perm_optional)
                        else -> stringResource(R.string.perm_required)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
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
