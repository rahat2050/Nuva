package com.nuva.assistant

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuva.assistant.automation.ExternalTextHandoffPolicy
import com.nuva.assistant.ui.NuvaApp
import com.nuva.assistant.ui.theme.NuvaTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single-activity Compose app. Voice-first home screen; History / Memory /
 * Settings are tabs of the same screen (blueprint §2.3).
 */
class MainActivity : ComponentActivity() {

    data class AssistantInvocation(
        val id: Long,
        val listenInApp: Boolean,
        val inlineCommand: String?,
        val draftText: String? = null,
    )

    private val assistantInvocation = MutableStateFlow<AssistantInvocation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publishAssistantInvocation(intent)
        enableEdgeToEdge()
        setContent {
            val invocation by assistantInvocation.collectAsState()
            NuvaTheme {
                NuvaApp(
                    assistantInvocation = invocation,
                    onAssistantInvocationConsumed = { consumedId ->
                        if (assistantInvocation.value?.id == consumedId) {
                            assistantInvocation.value = null
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishAssistantInvocation(intent)
    }

    private fun publishAssistantInvocation(intent: Intent?) {
        val incoming = intent ?: return
        val invocation = when (incoming.action) {
            ACTION_SYSTEM_ASSISTANT,
            Intent.ACTION_ASSIST,
            -> AssistantInvocation(
                id = nextInvocationId(),
                listenInApp = incoming.getBooleanExtra(EXTRA_LISTEN_IN_APP, true),
                inlineCommand = incoming.getStringExtra(EXTRA_INLINE_COMMAND)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )

            ACTION_QUICK_SPEAK -> AssistantInvocation(
                id = nextInvocationId(),
                listenInApp = true,
                inlineCommand = null,
            )

            Intent.ACTION_SEND -> externalDraftInvocation(
                incoming.getCharSequenceExtra(Intent.EXTRA_TEXT),
            )

            Intent.ACTION_PROCESS_TEXT -> externalDraftInvocation(
                incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            )

            else -> null
        }

        if (invocation != null) assistantInvocation.value = invocation
        if (incoming.action in HANDLED_ACTIONS) clearHandledPayload(incoming)
    }

    private fun externalDraftInvocation(raw: CharSequence?): AssistantInvocation {
        val draft = when (val result = ExternalTextHandoffPolicy.prepare(raw)) {
            is ExternalTextHandoffPolicy.Result.Accepted -> {
                if (result.truncated) {
                    Toast.makeText(this, "Shared text was limited to 1000 characters.", Toast.LENGTH_LONG).show()
                }
                result.draft
            }

            is ExternalTextHandoffPolicy.Result.Blocked -> {
                Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                null
            }

            ExternalTextHandoffPolicy.Result.Empty -> {
                Toast.makeText(this, "No readable text was shared.", Toast.LENGTH_SHORT).show()
                null
            }
        }
        return AssistantInvocation(
            id = nextInvocationId(),
            listenInApp = false,
            inlineCommand = null,
            draftText = draft,
        )
    }

    /** Remove shared text from Activity intent so recreation cannot replay/retain it. */
    private fun clearHandledPayload(intent: Intent) {
        intent.action = null
        intent.removeExtra(EXTRA_LISTEN_IN_APP)
        intent.removeExtra(EXTRA_INLINE_COMMAND)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        intent.clipData = null
    }

    private fun nextInvocationId(): Long = SystemClock.elapsedRealtimeNanos()

    companion object {
        const val ACTION_SYSTEM_ASSISTANT = "com.nuva.assistant.action.SYSTEM_ASSISTANT"
        const val ACTION_QUICK_SPEAK = "com.nuva.assistant.action.QUICK_SPEAK"
        const val EXTRA_LISTEN_IN_APP = "com.nuva.assistant.extra.LISTEN_IN_APP"
        const val EXTRA_INLINE_COMMAND = "com.nuva.assistant.extra.INLINE_COMMAND"

        private val HANDLED_ACTIONS = setOf(
            ACTION_SYSTEM_ASSISTANT,
            ACTION_QUICK_SPEAK,
            Intent.ACTION_ASSIST,
            Intent.ACTION_SEND,
            Intent.ACTION_PROCESS_TEXT,
        )
    }
}
