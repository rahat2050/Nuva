package com.nuva.assistant.command

import android.content.Context
import com.nuva.assistant.ai.AIRepository
import com.nuva.assistant.automation.AppLauncher
import com.nuva.assistant.automation.BrowserAutomation
import com.nuva.assistant.automation.DeviceStatusProvider
import com.nuva.assistant.automation.MessagingRegistry
import com.nuva.assistant.automation.ReminderOpener
import com.nuva.assistant.automation.SettingsOpener
import com.nuva.assistant.automation.SmsAutomation
import com.nuva.assistant.automation.WhatsAppAutomation
import com.nuva.assistant.automation.YouTubeAutomation
import com.nuva.assistant.contacts.ContactResolver
import com.nuva.assistant.database.dao.CommandHistoryDao
import com.nuva.assistant.database.dao.PendingActionDao
import com.nuva.assistant.database.dao.NoteDao
import com.nuva.assistant.database.dao.insert
import com.nuva.assistant.database.entities.NoteEntity
import com.nuva.assistant.memory.UserPreferences
import com.nuva.assistant.service.NuvaNotificationListener
import com.nuva.assistant.supabase.SupabaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE COMMAND ENGINE — the only class that turns a validated decision into
 * real device activity.
 *
 * Golden rule enforced here (blueprint §2.15 / docs §30):
 *   speech → interpret (AI or on-device parser) → LOCAL RE-VALIDATION →
 *   confirmation gate (blocking) → execute → report → speak result.
 *
 * v1.1 additions:
 *  * on-device parser runs FIRST (cheap, offline, privacy-friendly); the AI is
 *    consulted when it does not understand, and as a rescue path when the
 *    server marks a request unsupported;
 *  * contact-name resolution with an explicit multi-match choice step;
 *  * strict security denylist check right before execution (§32–§36);
 *  * failure reasons are written to history so the UI can explain + retry.
 */
class CommandExecutor(
    private val contextProvider: () -> Context,
    private val aiRepository: AIRepository,
    private val preferences: UserPreferences,
    private val history: CommandHistoryDao,
    private val pendingActions: PendingActionDao,
    private val supabaseRepository: SupabaseRepository,
    private val contactResolver: ContactResolver = ContactResolver(contextProvider),
    private val deviceStatus: DeviceStatusProvider = DeviceStatusProvider(contextProvider),
    private val notes: NoteDao? = null,
) {

    sealed interface Step {
        /** A decision arrived from the AI (or offline parser). */
        data class Decision(val decision: CommandDecision) : Step

        /** Medium/high risk — execution is BLOCKED until confirm()/reject(). */
        data class AwaitingConfirmation(val pendingId: Long, val decision: CommandDecision) : Step

        /**
         * Several contacts match the spoken name — the user must pick one
         * before the confirmation dialog. Nothing has executed yet.
         */
        data class AwaitingContactChoice(
            val pendingId: Long,
            val decision: CommandDecision,
            val matches: List<ContactResolver.ContactMatch>,
        ) : Step

        data class Executing(val action: NuvaAction) : Step
        data class Done(val speech: String, val status: String, val screenText: String? = null) : Step
        data class Failed(val speech: String) : Step
    }

    private val _busy = MutableStateFlow(false)
    val busy: Flow<Boolean> = _busy.asStateFlow()

    /**
     * Full pipeline for one utterance (voice or typed). Never throws — every
     * failure becomes a [Step.Failed] with a user-sayable reason.
     */
    suspend fun process(text: String): Step {
        if (text.isBlank()) return Step.Failed("Kichu bolejni. Ar ektu jore bolen.")
        _busy.value = true
        try {
            val decision = interpret(text) ?: return Step.Failed("Bujhte parini, ektu onno bhabe bolen.")
            return handleDecision(text, decision)
        } catch (err: AIRepository.ApiCallException) {
            return Step.Failed(err.speech)
        } catch (err: Exception) {
            return Step.Failed("Command ta process korte parini — abar try korun.")
        } finally {
            _busy.value = false
        }
    }

    /**
     * Interpretation strategy (v1.1):
     *  1. On-device parser first — deterministic, offline, free.
     *  2. If it does not know the pattern → the AI (via backend).
     *  3. Network down → on-device parser again (offline mode).
     */
    private suspend fun interpret(text: String): CommandDecision? {
        val local = CommandParser.parse(text)
        if (local != null && !local.unsupported) return local
        // Local parser explicitly asked for more info (or refused) — respect it.
        if (local != null && local.source == "offline-security") return local

        val language = preferences.languageBlocking()
        return try {
            val remote = aiRepository.interpret(text, language)
            if (remote.unsupported) {
                // Rescue: the server did not understand, maybe the local parser
                // can (its v2 rule set is broader for practical commands).
                local ?: remote
            } else {
                remote
            }
        } catch (err: Exception) {
            local ?: throw err
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
            history.updateStatusAndError(localId, "unsupported", decision.reasons.firstOrNull())
            reportRemote(decision, "unsupported", null)
            return Step.Done(speech, "unsupported")
        }

        var action = decision.action

        // Contact resolution for calls/messages BEFORE anything is confirmed,
        // so the confirmation dialog always shows a real number (§16–§19).
        when (action) {
            is NuvaAction.CallContact -> if (action.phoneNumber.isNullOrBlank()) {
                return when (val r = resolveContact(action.contact, localId, text, decision)) {
                    is ContactStep.Resolved -> {
                        action = action.copy(phoneNumber = r.number, contact = r.name ?: action.contact)
                        proceed(text, decision.copy(action = action), action, localId)
                    }

                    is ContactStep.Choice -> return r.step
                    is ContactStep.Fail -> return Step.Failed(r.speech).also {
                        history.updateStatusAndError(localId, "failed", r.reason)
                    }
                }
            }

            is NuvaAction.SendMessage -> if (action.phoneNumber.isNullOrBlank()) {
                return when (val r = resolveContact(action.contact, localId, text, decision)) {
                    is ContactStep.Resolved -> {
                        action = action.copy(phoneNumber = r.number, contact = r.name ?: action.contact)
                        proceed(text, decision.copy(action = action), action, localId)
                    }

                    is ContactStep.Choice -> return r.step
                    is ContactStep.Fail -> return Step.Failed(r.speech).also {
                        history.updateStatusAndError(localId, "failed", r.reason)
                    }
                }
            }

            else -> Unit
        }

        return proceed(text, decision, action, localId)
    }

    private sealed interface ContactStep {
        data class Resolved(val number: String, val name: String?) : ContactStep
        data class Choice(val step: Step.AwaitingContactChoice) : ContactStep
        data class Fail(val speech: String, val reason: String) : ContactStep
    }

    private suspend fun resolveContact(
        name: String,
        localId: Long,
        text: String,
        decision: CommandDecision,
    ): ContactStep {
        return when (val r = contactResolver.resolve(name)) {
            is ContactResolver.Resolution.NoPermission ->
                ContactStep.Fail(
                    "Contact dekhte permission lagbe — NUVA app e giye Contacts permission den, tarpor abar bolen.",
                    "contacts permission missing",
                )

            is ContactResolver.Resolution.NotFound ->
                ContactStep.Fail(
                    "\"$name\" nam er contact painai — number ta bole dileo call/message korte parbo.",
                    "contact not found",
                )

            is ContactResolver.Resolution.Single ->
                ContactStep.Resolved(r.match.phone, r.match.displayName)

            is ContactResolver.Resolution.Ambiguous -> {
                val pendingId = pendingActions.insert(
                    localCommandId = localId,
                    commandText = text,
                    actionJson = ActionJson.encode(decision.action!!),
                    risk = decision.risk.name.lowercase(),
                    serverCommandId = decision.commandId,
                )
                history.updateStatus(localId, "pending_choice")
                ContactStep.Choice(Step.AwaitingContactChoice(pendingId, decision, r.matches))
            }
        }
    }

    private suspend fun proceed(
        text: String,
        decision: CommandDecision,
        action: NuvaAction,
        localId: Long,
    ): Step {
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

    /** The user picked one of several matching contacts → park it and confirm. */
    suspend fun chooseContact(pendingId: Long, match: ContactResolver.ContactMatch): Step {
        val pending = pendingActions.get(pendingId) ?: return Step.Failed("Action ar pending nei.")
        val action = ActionJson.decode(pending.actionJson) ?: return Step.Failed("Action ta ar valid nei.")
        val updated: NuvaAction = when (action) {
            is NuvaAction.CallContact -> action.copy(contact = match.displayName, phoneNumber = match.phone)
            is NuvaAction.SendMessage -> action.copy(contact = match.displayName, phoneNumber = match.phone)
            else -> action
        }
        // Park the RESOLVED action — confirm() decodes from the DB row.
        pendingActions.updateAction(pendingId, ActionJson.encode(updated))
        pendingActions.updateStatus(pendingId, "pending")
        val decision = CommandDecision(
            intent = updated.intent,
            action = updated,
            unsupported = false,
            risk = NuvaRisk.valueOf(pending.risk.uppercase()),
            requiresConfirmation = true,
            speech = "",
            reasons = emptyList(),
            commandId = pending.serverCommandId,
            source = "contact-choice",
        )
        // Re-enter the confirmation gate with the resolved number.
        val mustConfirm = com.nuva.assistant.core.security.SecurityPolicy.mustConfirm(
            decision.risk,
            preferences.confirmationAlwaysBlocking(),
        ) || decision.requiresConfirmation
        return if (mustConfirm) {
            Step.AwaitingConfirmation(pendingId, decision)
        } else {
            confirm(pendingId)
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

            // STRICT SECURITY GATE (§32–§36) — last line of defence before any
            // action touches the device. Sensitive targets are refused here.
            com.nuva.assistant.core.security.SensitiveAppPolicy.refusalFor(action)?.let { refusal ->
                history.updateStatusAndError(localId, "blocked", refusal.reason)
                return Step.Failed(com.nuva.assistant.core.security.SensitiveAppPolicy.REFUSAL_SPEECH)
            }

            val outcome = execute(action)
            if (outcome.error != null) {
                history.updateStatusAndError(localId, outcome.status, outcome.error)
            } else {
                history.updateStatus(localId, outcome.status)
            }
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

                is AppLauncher.LaunchResult.NotFound -> {
                    // Play Store / search suggestion (requirement §7).
                    val opened = AppLauncher.openPlayStoreSearch(context, action.app)
                    if (opened) {
                        ExecutionOutcome(
                            "failed",
                            "${action.app} app ta install kora nai — Play Store e khujchi, install kore nin.",
                            "app not installed (Play Store suggested)",
                        )
                    } else {
                        ExecutionOutcome("failed", "${action.app} app ta paina.", "app not found")
                    }
                }
            }

            is NuvaAction.CloseApp -> if (AppLauncher.closeApp(context)) {
                ExecutionOutcome("completed", "${action.app} bondho korchi, home e jacchi.")
            } else {
                ExecutionOutcome("failed", "Home e jete parini.", "home intent failed")
            }

            is NuvaAction.GoHome -> if (globalHome()) {
                ExecutionOutcome("completed", "Home e jacchi.")
            } else {
                ExecutionOutcome("failed", "Home e jete parini.", "home intent failed")
            }

            is NuvaAction.GoBack -> if (globalBack()) {
                ExecutionOutcome("completed", "Pichone jacchi.")
            } else {
                ExecutionOutcome("failed", "Pichone jete parini.", "global action failed")
            }

            is NuvaAction.ShowRecents -> {
                val service = com.nuva.assistant.accessibility.NuvaAccessibilityService.instance
                if (service != null && service.showRecents()) {
                    ExecutionOutcome("completed", "Recent app dekhacchi.")
                } else {
                    ExecutionOutcome(
                        "failed",
                        "Recent apps dekhate Accessibility permission lagbe — NUVA Settings e guide ache.",
                        "accessibility missing",
                    )
                }
            }

            is NuvaAction.Tap, is NuvaAction.TypeText, is NuvaAction.Swipe, is NuvaAction.Scroll ->
                when (val r = com.nuva.assistant.automation.GenericAutomation.execute(action)) {
                    is com.nuva.assistant.automation.GenericAutomation.Outcome.Success ->
                        ExecutionOutcome("completed", "Kore diachi.")

                    is com.nuva.assistant.automation.GenericAutomation.Outcome.Failure ->
                        ExecutionOutcome("failed", r.userReason, r.userReason)
                }

            is NuvaAction.ReadScreen -> {
                val service = com.nuva.assistant.accessibility.NuvaAccessibilityService.instance
                if (service == null) {
                    ExecutionOutcome(
                        "failed",
                        "Screen porte Accessibility permission lagbe — Settings e giye NUVA on korun.",
                        "accessibility missing",
                    )
                } else if (service.isForegroundSensitive()) {
                    ExecutionOutcome(
                        "failed",
                        com.nuva.assistant.core.security.SensitiveAppPolicy.SCREEN_GUARD_SPEECH,
                        "blocked: sensitive screen",
                    )
                } else {
                    when (val r = com.nuva.assistant.accessibility.ScreenReader.read(action.scope)) {
                        is com.nuva.assistant.accessibility.ScreenReader.ReadResult.Success ->
                            ExecutionOutcome("completed", screenSpeech(r.text), null, r.text)

                        is com.nuva.assistant.accessibility.ScreenReader.ReadResult.ServiceMissing ->
                            ExecutionOutcome("failed", r.reason, "accessibility missing")

                        is com.nuva.assistant.accessibility.ScreenReader.ReadResult.Empty ->
                            ExecutionOutcome("failed", r.reason, "screen empty")
                    }
                }
            }

            is NuvaAction.ReadNotifications -> when (val s = NuvaNotificationListener.summary()) {
                is NuvaNotificationListener.Summary.Ready ->
                    ExecutionOutcome("completed", screenSpeech(s.text), null, s.text)

                is NuvaNotificationListener.Summary.Empty ->
                    ExecutionOutcome("completed", s.text, null, s.text)

                NuvaNotificationListener.Summary.NeedsAccess -> {
                    NuvaNotificationListener.openAccessSettings(context)
                    ExecutionOutcome(
                        "failed",
                        "Notification porte \"Notification access\" lagbe — setting ta khule dicchi, NUVA te on korun.",
                        "notification access missing",
                    )
                }
            }

            is NuvaAction.SearchWeb -> when (val r = BrowserAutomation.searchWeb(context, action.query)) {
                is BrowserAutomation.Result.Opened -> ExecutionOutcome("completed", "\"${action.query}\" khujchi.")
                is BrowserAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.DeviceStatusQuery ->
                ExecutionOutcome("completed", deviceStatus.answer(action.query))

            is NuvaAction.OpenSettingScreen -> when (val r = SettingsOpener.open(context, action.target)) {
                is SettingsOpener.Result.Done -> ExecutionOutcome("completed", "Kore dilam.")
                is SettingsOpener.Result.ManualStep -> ExecutionOutcome("completed", r.speech)
                is SettingsOpener.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.SetReminder -> {
                val spokenWhen = action.humanWhen?.let { " ($it)" } ?: ""
                when (val r = ReminderOpener.open(context, action)) {
                    is ReminderOpener.Result.Opened ->
                        ExecutionOutcome("completed", "Reminder: ${action.title}$spokenWhen — calendar khulchi, Save chapun.")

                    is ReminderOpener.Result.Failed ->
                        ExecutionOutcome("failed", r.userReason, r.userReason)
                }
            }

            is NuvaAction.CreateNote -> {
                val dao = notes ?: return ExecutionOutcome("failed", "Note rakhar jayga painai.", "notes storage unavailable")
                val id = dao.insertRow(NoteEntity(content = action.content, kind = "note"))
                ExecutionOutcome("completed", if (id > 0) "Note kore nilam." else "Note rakhte parini.")
            }

            is NuvaAction.CreateTodo -> {
                val dao = notes ?: return ExecutionOutcome("failed", "Kaj rakhar jayga painai.", "notes storage unavailable")
                val id = dao.insertRow(NoteEntity(content = action.content, kind = "todo"))
                ExecutionOutcome("completed", if (id > 0) "Kaj ta list e rakhlam." else "Kaj ta rakhte parini.")
            }

            is NuvaAction.CallContact -> {
                val number = action.phoneNumber
                if (number.isNullOrBlank()) {
                    ExecutionOutcome("failed", "${action.contact} er number painai.", "no phone number")
                } else {
                    val direct = preferences.directCallBlocking()
                    val ok = AppLauncher.dial(context, number, direct)
                    if (ok) {
                        ExecutionOutcome("completed", "${action.contact} ke call korchi.")
                    } else {
                        ExecutionOutcome("failed", "Call korte parini.", "dial failed")
                    }
                }
            }

            is NuvaAction.SendMessage -> executeSendMessage(context, action)

            is NuvaAction.SetAlarm -> {
                val ok = AppLauncher.setAlarm(context, action.hour, action.minute, action.label, action.relativeDay, action.days)
                val time = "%02d:%02d".format(action.hour, action.minute)
                if (ok) {
                    ExecutionOutcome("completed", "$time alarm set korchi.")
                } else {
                    ExecutionOutcome("failed", "Alarm set korte parini.", "alarm intent failed")
                }
            }

            is NuvaAction.SetTimer -> {
                val ok = AppLauncher.setTimer(context, action.durationSeconds, action.label)
                val minutes = action.durationSeconds / 60
                val human = if (minutes >= 1) "$minutes minute" else "${action.durationSeconds} second"
                if (ok) {
                    ExecutionOutcome("completed", "$human er timer set korchi.")
                } else {
                    ExecutionOutcome("failed", "Timer set korte parini.", "timer intent failed")
                }
            }

            is NuvaAction.OpenUrl -> when (val r = BrowserAutomation.navigate(context, action.url)) {
                is BrowserAutomation.Result.Opened -> ExecutionOutcome("completed", "Page ta khulchi.")
                is BrowserAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.PlayMedia -> when (action.app) {
                null, MediaApp.YOUTUBE ->
                    when (val r = YouTubeAutomation.searchAndPlay(context, action, autoplayFirstResult = true)) {
                        is YouTubeAutomation.Result.Playing -> ExecutionOutcome("completed", "Chaliye dicchi.")
                        is YouTubeAutomation.Result.SearchReady -> ExecutionOutcome("completed", "Search kore dicchi, result dekhe nin.")
                        is YouTubeAutomation.Result.Failed -> ExecutionOutcome("failed", r.userReason, r.userReason)
                    }

                MediaApp.SPOTIFY ->
                    when (val r = AppLauncher.openApp(context, "spotify", "com.spotify.music")) {
                        is AppLauncher.LaunchResult.Success ->
                            when (val yt = YouTubeAutomation.searchAndPlay(context, action, autoplayFirstResult = false)) {
                                is YouTubeAutomation.Result.Failed -> ExecutionOutcome("completed", "Spotify khulchi.")
                                else -> ExecutionOutcome("completed", "Spotify khulchi.")
                            }

                        is AppLauncher.LaunchResult.NotFound ->
                            when (val yt = YouTubeAutomation.searchAndPlay(context, action, autoplayFirstResult = true)) {
                                is YouTubeAutomation.Result.Failed -> ExecutionOutcome("failed", yt.userReason, yt.userReason)
                                else -> ExecutionOutcome("completed", "Spotify nai, YouTube e khujchi.")
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
        // Unsupported messaging apps say so clearly (requirement §20).
        if (!MessagingRegistry.isSupported(action.app)) {
            return ExecutionOutcome("failed", MessagingRegistry.unsupportedReason(action.app), "messaging app not supported")
        }

        if (action.phoneNumber.isNullOrBlank() && action.app == MessagingApp.WHATSAPP) {
            // Contact resolution could not find a number — WhatsApp can still
            // open; the user taps the chat themselves.
            return when (AppLauncher.openApp(context, "whatsapp", "com.whatsapp")) {
                is AppLauncher.LaunchResult.Success ->
                    ExecutionOutcome("completed", "WhatsApp khulchi — chat ta ber kore nin.")

                is AppLauncher.LaunchResult.NotFound ->
                    ExecutionOutcome("failed", "WhatsApp install kora nai.", "whatsapp missing")
            }
        }

        return when (action.app) {
            MessagingApp.WHATSAPP -> when (val r = WhatsAppAutomation.sendMessage(context, action)) {
                is WhatsAppAutomation.Result.Sent ->
                    ExecutionOutcome("completed", "${action.contact} ke message pathiyeci.")

                is WhatsAppAutomation.Result.Failed ->
                    ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            MessagingApp.SMS -> {
                val number = action.phoneNumber
                    ?: return ExecutionOutcome("failed", "${action.contact} er number painai.", "no phone number")
                when (val r = SmsAutomation.sendOrCompose(context, number, action.message)) {
                    is SmsAutomation.Result.Sent ->
                        ExecutionOutcome("completed", "${action.contact} ke SMS pathiyeci.")

                    is SmsAutomation.Result.ComposeOpened ->
                        ExecutionOutcome("completed", r.reason)

                    is SmsAutomation.Result.Failed ->
                        ExecutionOutcome("failed", r.userReason, r.userReason)
                }
            }

            else -> ExecutionOutcome("failed", MessagingRegistry.unsupportedReason(action.app), "unsupported")
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
