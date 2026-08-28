package com.nuva.assistant.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuva.assistant.R
import com.nuva.assistant.ui.theme.NuvaGlassPanel
import com.nuva.assistant.ui.theme.NuvaScreenHeader

/**
 * Privacy screen (v1.6, Phase 21): exactly what happens to the user's data —
 * what stays on the phone, what is sent to Groq (via the Vercel backend),
 * what is stored where. No telemetry exists in the app.
 */
@Composable
fun PrivacyScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvaScreenHeader(
            eyebrow = "TRANSPARENT DATA FLOW",
            title = stringResource(R.string.privacy_title),
            subtitle = "কোন data ফোনে থাকে এবং কখন text বাইরে যায়—সব এক জায়গায়",
        )

        PrivacyCard(
            title = stringResource(R.string.privacy_local_title),
            body = stringResource(R.string.privacy_local_body),
        )
        PrivacyCard(
            title = stringResource(R.string.privacy_groq_title),
            body = stringResource(R.string.privacy_groq_body),
        )
        PrivacyCard(
            title = stringResource(R.string.privacy_vercel_title),
            body = stringResource(R.string.privacy_vercel_body),
        )
        PrivacyCard(
            title = stringResource(R.string.privacy_localdb_title),
            body = stringResource(R.string.privacy_localdb_body),
        )
        PrivacyCard(
            title = stringResource(R.string.privacy_never_title),
            body = stringResource(R.string.privacy_never_body),
            highlight = true,
        )
    }
}

@Composable
private fun PrivacyCard(title: String, body: String, highlight: Boolean = false) {
    val accent = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    NuvaGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
