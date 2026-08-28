package com.nuva.assistant

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        val action = intent?.action ?: return
        if (action != ACTION_SYSTEM_ASSISTANT && action != Intent.ACTION_ASSIST) return
        assistantInvocation.value = AssistantInvocation(
            id = SystemClock.elapsedRealtimeNanos(),
            listenInApp = intent.getBooleanExtra(EXTRA_LISTEN_IN_APP, true),
            inlineCommand = intent.getStringExtra(EXTRA_INLINE_COMMAND)
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
        )
        // Do not replay assistant invocation after Activity recreation.
        intent.action = null
        intent.removeExtra(EXTRA_LISTEN_IN_APP)
        intent.removeExtra(EXTRA_INLINE_COMMAND)
    }

    companion object {
        const val ACTION_SYSTEM_ASSISTANT = "com.nuva.assistant.action.SYSTEM_ASSISTANT"
        const val EXTRA_LISTEN_IN_APP = "com.nuva.assistant.extra.LISTEN_IN_APP"
        const val EXTRA_INLINE_COMMAND = "com.nuva.assistant.extra.INLINE_COMMAND"
    }
}
