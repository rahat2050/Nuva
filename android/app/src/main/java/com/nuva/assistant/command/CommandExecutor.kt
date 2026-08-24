package com.nuva.assistant.command

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.ai.AIRepository
import com.nuva.assistant.automation.AppLauncher
import com.nuva.assistant.automation.BrowserAutomation
import com.nuva.assistant.automation.GenericAutomation
import com.nuva.assistant.automation.WhatsAppAutomation
import com.nuva.assistant.automation.YouTubeAutomation
import com.nuva.assistant.database.dao.CommandHistoryDao
import com.nuva.assistant.database.dao.PendingActionDao
import com.nuva.assistant.database.entities.PendingActionEntity
import com.nuva.assistant.database.dao.insert
import com.nuva.assistant.memory.UserPreferences
import com.nuva.assistant.supabase.SupabaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE COMMAND ENGINE (roadmap steps 5–9) — the only class that turns a
 * validated decision into real device activity.
 *
 * Golden rule enforced here (blueprint §2.15 / docs §30):
 *   speech → interpret (AI or offline) → LOCAL RE-VALIDATION → confirmation
 *   gate (blocking) → execute → report → speak result.
 *
 * It only ever executes [NuvaAction] instances that already passed
 * [CommandValidator] — there is no code path from raw model output to
 * execution.
 */
class CommandExecutor(
    private val contextProvider: () -> Context,
    private val aiRepository: AIRepository,
    private val preferences: UserPreferences,
    private val history: CommandHistoryDao,
    private val pendingActions: PendingActionDao,
    private val supabaseRepository: SupabaseRepository,
) {

    sealed interface Step {
        /** A decision arrived from the AI (or offline parser). */
        data class Decision(val decision: CommandDecision) : Step

        /** Medium/high risk — execution is BLOCKED until confirm()/reject(). */
        data class AwaitingConfirmation(val pendingId: Long, val decision: CommandDecision) : Step

        data class Executing(val action: NuvaAction) : Step
        data class Done(val speech: String, val status: String, val screenText: String? = null) : Step
        data class Failed(val speech: String) : Step
    }

    private val _busy = MutableStateFlow(false)
    val busy: Flow<Boolean> = _busy.asStateFlow()

    /**
     * Full pipeline for one utterance. Network failure automatically degrades
     * to the offline parser for the simple low-risk subset (§2.21).
     */
    suspend fun process(text: String): Step {
        if (text.isBlank()) return Step.Failed("Kichu bolejni. Ar ektu jore bolen.")
        _busy.value = true
        try {
            val decision = interpret(text)
            return handleDecision(text, decision)
        } catch (err: AIRepository.ApiCallException) {
            return Step.Failed(err.speech)
        } catch (err: Exception) {
            return Step.Failed("Internet e pouchate parchi na.")
        } finally {
            _busy.value = false
        }
    }

    private suspend fun interpret(text: String): CommandDecision {
        val language = preferences.languageBlocking()
        return try {
            aiRepository.interpret(text, language)
        } catch (err: Exception) {
            // Offline fallback — only simple, low-risk commands (§2.21).
            CommandParser.parse(text)
                ?: throw err
        }
    }

    private suspend fun handleDecision(text: String, decision: CommandDecision): Step {
        // Record locally FIRST — the audit trail must exist even if execution fails.
        val localId = history.insert(
            text = text,
            intent = decision.intent?.wireName ?: "UNSUPPORTED",
            risk = decision.risk.name.lowercase(),
            status = if (decision.unsupported) "unsupported" else "ready",
        )

        if (decision.unsupported || decision.action == null) {
            val speech = decision.speech.ifBlank { "Eta ami korte pari na." }
            reportRemote(decision, "unsupported", null)
            return Step.Done(speech, "unsupported")
        }

        val action = decision.action
        val mustConfirm = com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(
            decision.risk,
            preferences.confirmationAlwaysBlocking(),
        ) || decision.requiresConfirmation

        return if (mustConfirm) {
            val pendingId = pendingActions.insert(
                localCommandId = localId,
                commandText = text,
                actionJson = ActionJson.encode(action),
                risk = decision.risk.name.lowercase(),
                serverCommandId = decision.commandId,
            )
            history.updateStatus(localId, "pending_confirmation")
            Step.AwaitingConfirmation(pendingId, decision)
        } else {
            executeDecision(decision, action, localId)
        }
    }

    /** Called by the confirmation UI when the user approves a pending action. */
    suspend fun confirm(pendingId: Long): Step {
        val pending = pendingActions.get(pendingId) ?: return Step.Failed("Action ar pending nei.")
        val action = ActionJson.decode(pending.actionJson)
            ?: return Step.Failed("Action ta ar valid nei.")
        pendingActions.updateStatus(pendingId, "confirmed")
        if (pending.localCommandId != null) {
            history.updateStatus(pending.localCommandId, "confirmed")
        }
        val decision = CommandDecision(
            intent = action.intent,
            action = action,
            unsupported = false,
            risk = NuvaRisk.valueOf(pending.risk.uppercase()),
            requiresConfirmation = true,
            speech = "",
            reasons = emptyList(),
            commandId = pending.serverCommandId,
            source = "pending",
        )
        return executeDecision(decision, action, pending.localCommandId ?: 0L)
    }

    /** Called when the user rejects the confirmation dialog. */
    suspend fun reject(pendingId: Long): Step {
        val pending = pendingActions.get(pendingId) ?: return Step.Done("Thik ache, koreni.", "rejected")
        pendingActions.updateStatus(pendingId, "rejected")
        if (pending.localCommandId != null) history.updateStatus(pending.localCommandId, "rejected")
        reportRemoteById(pending.serverCommandId, "rejected", null)
        return Step.Done("Thik ache, koreni.", "rejected")
    }

    // --- Execution -------------------------------------------------------------

    private suspend fun executeDecision(
        decision: CommandDecision,
        action: NuvaAction,
        localId: Long,
    ): Step {
        _busy.value = true
        try {
            history.updateStatus(localId, "executing")
            val outcome = execute(action)
            history.updateStatus(localId, outcome.status)
            reportRemote(decision, outcome.status, outcome.error)
            return when (outcome.status) {
                "completed" -> Step.Done(outcome.speech, "completed", outcome.screenText)
                else -> Step.Failed(outcome.speech)
            }
        } finally {
            _busy.value = false
        }
    }

    data class ExecutionOutcome(val status: String, val speech: String, val error: String? = null, val screenText: String? = null)

    private suspend fun execute(action: NuvaAction): ExecutionOutcome {
        val context = contextProvider()
        return when (action) {
            is NuvaAction.OpenApp -> when (val r = AppLauncher.openApp(context, action.app, action.pkg)) {
                is AppLauncher.LaunchResult.Success ->
                    ExecutionOutcome("completed", "${action.app.replaceFirstChar { it.uppercase() }} khulchi.")
                is AppLauncher.LaunchResult.NotFound ->
                    ExecutionOutcome("failed", "${action.app} app ta paina.", "app not found")
            }

            is NuvaAction.CloseApp -> if (AppLauncher.closeApp(context)) {
                ExecutionOutcome("completed", "${action.app} bondho korchi, home e jacchi.")
            } else {
                ExecutionOutcome("failed", "Home e jete parini.", "home intent failed")
            }

            is NuvaAction.GoHome -> if (globalHome()) {
                ExecutionOutcome("completed", "Home e jacchi.")
            } else {
                ExecutionOutcome("failed", "Home e jete parini.", "global action failed")
            }

            is NuvaAction.GoBack -> if (globalBack()) {
                ExecutionOutcome("completed", "Pichone jacchi.")
            } else {
                ExecutionOutcome("failed", "Pichone jete parini.", "global action failed")
            }

            is NuvaAction.Tap, is NuvaAction.TypeText, is NuvaAction.Swipe, is NuvaAction.Scroll ->
                when (val r = GenericAutomation.execute(action)) {
                    is GenericAutomation.Outcome.Success ->
                        ExecutionOutcome("completed", "Kore diachi.")
                    is GenericAutomation.Outcome.Failure ->
                        ExecutionOutcome("failed", r.userReason, r.userReason)
                }

            is NuvaAction.ReadScreen -> when (val r = com.nuva.assistant.accessibility.ScreenReader.read(action.scope)) {
                is com.nuva.assistant.accessibility.ScreenReader.ReadResult.Success ->
                    ExecutionOutcome("completed", screenSpeech(r.text), null, r.text)
                is com.nuva.assistant.accessibility.ScreenReader.ReadResult.ServiceMissing ->
                    ExecutionOutcome("failed", r.reason, "accessibility missing")
                is com.nuva.assistant.accessibility.ScreenReader.ReadResult.Empty ->
                    ExecutionOutcome("failed", r.reason, "screen empty")
            }

            is NuvaAction.CallContact -> {
                val number = action.phoneNumber
                if (number.isNullOrBlank()) {
                    // Contact-name resolution needs the Contacts permission NUVA
                    // deliberately does not request; ask for the number instead.
                    ExecutionOutcome("failed", "${action.contact} er number ta bole din.", "no phone number")
                } else {
                    val direct = preferences.directCallBlocking()
                    val ok = AppLauncher.dial(context, number, direct)
                    if (ok) ExecutionOutcome("completed", "${action.contact} ke call korchi.") else ExecutionOutcome("failed", "Call korte parini.", "dial failed")
                }
            }

            is NuvaAction.SendMessage -> executeSendMessage(context, action)

            is NuvaAction.SetAlarm -> {
                val ok = AppLauncher.setAlarm(context, action.hour, action.minute, action.label, action.relativeDay, action.days)
                val time = "%02d:%02d".format(action.hour, action.minute)
                if (ok) ExecutionOutcome("completed", "$time alarm set korchi.") else ExecutionOutcome("failed", "Alarm set korte parini.", "alarm intent failed")
            }

            is NuvaAction.SetTimer -> {
                val ok = AppLauncher.setTimer(context, action.durationSeconds, action.label)
                val minutes = action.durationSeconds / 60
                val human = if (minutes >= 1) "$minutes minute" else "${action.durationSeconds} second"
                if (ok) ExecutionOutcome("completed", "$human er timer set korchi.") else ExecutionOutcome("failed", "Timer set korte parini.", "timer intent failed")
            }

            is NuvaAction.OpenUrl -> when (val r = BrowserAutomation.navigate(context, action.url)) {
                is BrowserAutomation.Result.Opened -> ExecutionOutcome("completed", "Page ta khulchi.")
                is BrowserAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.PlayMedia -> when (action.app) {
                null, com.nuva.assistant.command.MediaApp.YOUTUBE ->
                    when (val r = YouTubeAutomation.searchAndPlay(context, action, autoplayFirstResult = true)) {
                        is YouTubeAutomation.Result.Playing -> ExecutionOutcome("completed", "Chaliye dicchi.")
                        is YouTubeAutomation.Result.SearchReady -> ExecutionOutcome("completed", "Search kore dicchi, result dekhe nin.")
                        is YouTubeAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
                    }

                com.nuva.assistant.command.MediaApp.SPOTIFY ->
                    when (val r = AppLauncher.openApp(context, "spotify", "com.spotify.music")) {
                        is AppLauncher.LaunchResult.Success -> ExecutionOutcome("completed", "Spotify khulchi.")
                        is AppLauncher.LaunchResult.NotFound ->
                            ExecutionOutcome("completed", "Spotify nai, YouTube e khujchi.").let {
                                when (val yt = YouTubeAutomation.searchAndPlay(context, action, autoplayFirstResult = true)) {
                                    is YouTubeAutomation.Result.Failed -> ExecutionOutcome("failed", yt.userReason, yt.userReason)
                                    else -> it
                                }
                            }
                    }

                else -> when (val r = BrowserAutomation.searchWeb(context, action.query)) {
                    is BrowserAutomation.Result.Opened -> ExecutionOutcome("completed", "Khujchi.")
                    is BrowserAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
                }
            }
        }
    }

    private suspend fun executeSendMessage(context: Context, action: NuvaAction.SendMessage): ExecutionOutcome {
        return when (action.app) {
            com.nuva.assistant.command.MessagingApp.WHATSAPP ->
                when (val r = WhatsAppAutomation.sendMessage(context, action)) {
                    is WhatsAppAutomation.Result.Sent -> ExecutionOutcome("completed", "${action.contact} ke message pathiyeci.")
                    is WhatsAppAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
                }

            com.nuva.assistant.command.MessagingApp.SMS -> {
                val number = action.phoneNumber
                if (number.isNullOrBlank()) {
                    ExecutionOutcome("failed", "${action.contact} er number ta bole din.", "no phone number")
                } else {
                    val uri = Uri.parse("smsto:${number.filter { it.isDigit() || it == '+' }}")
                    val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                        putExtra("sms_body", action.message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    return try {
                        context.startActivity(intent)
                        ExecutionOutcome("completed", "SMS screen khulchi, message ready.")
                    } catch (err: Exception) {
                        ExecutionOutcome("failed", "SMS khulte parini.", "sms intent failed")
                    }
                }
            }

            else -> ExecutionOutcome(
                "failed",
                "${action.app.wireName} automation ekhon asche — WhatsApp ar SMS ekhon kaj kore.",
                "app not supported yet",
            )
        }
    }

    private fun screenSpeech(text: String): String {
        // Keep the spoken version short — the full text is shown in the UI.
        val trimmed = text.trim()
        return if (trimmed.length <= 600) trimmed else trimmed.take(600) + " …"
    }

    private fun globalHome(): Boolean =
        com.nuva.assistant.accessibility.NuvaAccessibilityService.instance?.goHome() ?: run {
            AppLauncher.closeApp(contextProvider()) // best-effort intent fallback
        }

    private fun globalBack(): Boolean =
        com.nuva.assistant.accessibility.NuvaAccessibilityService.instance?.goBack() ?: false

    // --- Reporting -------------------------------------------------------------

    private suspend fun reportRemote(decision: CommandDecision, status: String, error: String?) {
        reportRemoteById(decision.commandId, status, error)
    }

    private suspend fun reportRemoteById(commandId: String?, status: String, error: String?) {
        if (commandId == null) return // offline/local-only command — history row suffices
        try {
            supabaseRepository.reportExecution(commandId, status, error)
        } catch (err: Exception) {
            // Best effort: the local Room history is the source of truth offline.
        }
    }
}
