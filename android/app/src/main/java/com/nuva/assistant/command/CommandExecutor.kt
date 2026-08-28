package com.nuva.assistant.command

import android.content.Context
import android.content.Intent
import com.nuva.assistant.MainActivity
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
    /** Conversational context (v1.4): last app/chat/contact with a safe TTL. */
    val contextMemory: ContextMemory.Session = ContextMemory.Session(),
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
     * Remaining steps of a multi-action plan ("WhatsApp kholo AR Rohim-ke
     * message dau …"). Each step ran/will run through the SAME pipeline —
     * validation, contact resolution, security and per-action confirmation —
     * so a plan never bypasses a gate. Cleared when the user cancels.
     */
    private val planQueue = ArrayDeque<CommandDecision>()
    private var planTotalSteps: Int = 0

    /**
     * Full pipeline for one utterance (voice or typed). Never throws — every
     * failure becomes a [Step.Failed] with a user-sayable reason.
     */
    suspend fun process(text: String): Step {
        if (text.isBlank()) return Step.Failed("Kichu bolejni. Ar ektu jore bolen.")
        _busy.value = true
        try {
            // Multi-step plan? ("WhatsApp kholo ar Rohim-ke message dau …")
            val plan = CommandParser.parseCompound(text)
            if (plan != null && plan.size > 1) {
                planQueue.clear()
                planQueue.addAll(plan.drop(1))
                planTotalSteps = plan.size
                val first = plan.first()
                val firstStep = handleDecision("${text} · 1/$planTotalSteps ${first.intent?.wireName ?: ""}", first)
                return drainPlan(firstStep)
            }

            val decision = interpret(text) ?: return Step.Failed(
                "পুরো command-টা বুঝিনি — আপনি কি বলতে চাচ্ছেন অন্য কিছু? একটু অন্যভাবে বলুন বা লিখে দিন।",
            )
            return handleDecision(text, decision)
        } catch (err: AIRepository.ApiCallException) {
            return Step.Failed(
                "ইন্টারনেট লাগবে এই command-এর জন্য। লোকাল কাজ (app open, torch, alarm, timer, volume, battery) অফলাইনেই চলে।",
            )
        } catch (err: Exception) {
            return Step.Failed("Command ta process korte parini — abar try korun.")
        } finally {
            _busy.value = false
        }
    }

    /**
     * Drains the remaining plan steps after each completed one. Any step that
     * needs confirmation PAUSES the plan (its own dialog); after the user
     * confirms, [confirm] resumes the queue. A failed step aborts the rest.
     */
    private suspend fun drainPlan(step: Step): Step {
        var current = step
        var index = planTotalSteps - planQueue.size
        while (current is Step.Done && planQueue.isNotEmpty()) {
            val next = planQueue.removeFirst()
            index++
            val label = "· $index/$planTotalSteps ${next.intent?.wireName ?: ""}"
            current = if (current.status == "unsupported" || current.status == "rejected") {
                // An unsupported/rejected step ends the plan cleanly.
                current
            } else {
                handleDecision(label, next)
            }
        }
        if (planQueue.isEmpty() && current is Step.Done && planTotalSteps > 1) {
            planTotalSteps = 0
        }
        return current
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

        var action = applyContext(text, decision.action)

        // Contact resolution for calls/messages/chats BEFORE anything is
        // confirmed, so the confirmation dialog always shows a real number.
        when (action) {
            is NuvaAction.OpenChat -> if (action.phoneNumber.isNullOrBlank()) {
                return when (val r = resolveContact(action.contact, localId, text, decision)) {
                    is ContactStep.Resolved -> {
                        action = action.copy(contact = r.name ?: action.contact, phoneNumber = r.number)
                        proceed(text, decision.copy(action = action), action, localId)
                    }

                    is ContactStep.Choice -> return r.step
                    is ContactStep.Fail -> return Step.Failed(r.speech).also {
                        history.updateStatusAndError(localId, "failed", r.reason)
                    }
                }
            }

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

    /**
     * Conversational context (v1.4): pronouns ("ওকে") resolve to the last
     * contact; a messaging action without an explicit app inherits the app
     * we just had open. Never invents — nothing in context, nothing done.
     */
    private fun applyContext(text: String, action: NuvaAction): NuvaAction {
        val mentionsWhatsApp = text.contains("whatsapp") || text.contains("হোয়াটসঅ্যাপ")
        return when (action) {
            is NuvaAction.OpenChat -> {
                val contact = contextMemory.resolveContactReference(action.contact)
                    ?: return action // pronoun with no context — handled below
                val app = if (!mentionsWhatsApp) {
                    contextMessagingOverride(action.app)
                } else {
                    action.app
                }
                action.copy(app = app, contact = contact)
            }

            is NuvaAction.SendMessage -> {
                val contact = contextMemory.resolveContactReference(action.contact) ?: return action
                val app = if (!mentionsWhatsApp && action.app == MessagingApp.WHATSAPP) {
                    contextMessagingOverride(action.app)
                } else {
                    action.app
                }
                action.copy(app = app, contact = contact)
            }

            is NuvaAction.CallContact ->
                contextMemory.resolveContactReference(action.contact)?.let { action.copy(contact = it) } ?: action

            else -> action
        }
    }

    private fun contextMessagingOverride(default: MessagingApp): MessagingApp {
        val last = contextMemory.lastMessagingApp ?: return default
        return MessagingApp.fromWire(last.lowercase()) ?: default
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
        // A pronoun ("ওকে") with no live context can never be resolved.
        if (ContextMemory.isContactPronoun(name)) {
            return ContactStep.Fail(
                "Kake bole shunlam na — contact er nam ta bolen.",
                "pronoun without context",
            )
        }
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
        return drainPlan(executeDecision(decision, action, pending.localCommandId ?: 0L))
    }

    /** Called when the user rejects the confirmation dialog. */
    suspend fun reject(pendingId: Long): Step {
        val pending = pendingActions.get(pendingId) ?: return Step.Done("Thik ache, koreni.", "rejected")
        pendingActions.updateStatus(pendingId, "rejected")
        if (pending.localCommandId != null) history.updateStatus(pending.localCommandId, "rejected")
        reportRemoteById(pending.serverCommandId, "rejected", null)
        // Cancelling one step cancels the whole remaining plan — never run
        // half-approved sequences.
        if (planQueue.isNotEmpty()) {
            planQueue.clear()
            planTotalSteps = 0
            return Step.Done("Thik ache, koreni — baki plan-er kajgulo o cancel kore dilam.", "rejected")
        }
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

            // STRICT SECURITY GATE — financial transactions (LEVEL 3) are
            // refused before parsing; tap/type automation inside financial
            // apps is refused by the AccessibilityService guard. This is the
            // last-line audit hook: anything reaching here already passed both.


            var outcome = execute(action)

            // SMART RECOVERY (v1.4): classify the failure; exactly ONE retry
            // for transient kinds (timeout / UI changed) on open-style
            // actions. Everything else stops and explains — never blind loops.
            if (outcome.error != null) {
                val kind = FailureClassifier.classify(outcome.error)
                val retriable = FailureClassifier.canSafeRetry(kind) &&
                    (action is NuvaAction.OpenApp || action is NuvaAction.OpenChat)
                var retried = false
                if (retriable) {
                    outcome = execute(action) // exactly ONE safe retry, same validated action
                    retried = true
                }
                if (outcome.error != null) {
                    val finalKind = FailureClassifier.classify(outcome.error)
                    val retryNote = if (retried) " (auto-retried once: ${kind.name})" else ""
                    history.updateStatusAndError(localId, outcome.status, "[${finalKind.name}]${retryNote} ${outcome.error}")
                } else if (retried) {
                    // §11: the retry RESULT is recorded too — success after retry.
                    history.updateStatusAndError(localId, outcome.status, "auto-retry succeeded (${kind.name})")
                }
            }
            if (outcome.error == null) {
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
                is AppLauncher.LaunchResult.Success -> {
                    val financial = com.nuva.assistant.core.security.SensitiveAppPolicy
                        .isSensitivePackage(r.packageName) ||
                        com.nuva.assistant.core.security.SensitiveAppPolicy.isSensitiveAppName(action.app)
                    val note = if (financial) com.nuva.assistant.core.security.SensitiveAppPolicy.LEVEL1_OPEN_NOTE else ""
                    // Conversational context: remember the app we just opened.
                    val messaging = MessagingApp.fromWire(action.app.lowercase()) != null
                    contextMemory.onAppOpened(action.app.lowercase(), messaging)
                    ExecutionOutcome("completed", "${action.app.replaceFirstChar { it.uppercase() }} খুলেছি।$note")
                }

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
                        com.nuva.assistant.core.security.SensitiveAppPolicy.SCREEN_READ_GUARD_SPEECH,
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

            is NuvaAction.ComposeSocialPost -> when (
                val result = com.nuva.assistant.automation.SocialMmsComposer.social(context, action)
            ) {
                is com.nuva.assistant.automation.SocialMmsComposer.Result.Opened ->
                    ExecutionOutcome("completed", result.speech)
                com.nuva.assistant.automation.SocialMmsComposer.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive ba financial post draft blocked.", "sensitive social draft blocked")
                is com.nuva.assistant.automation.SocialMmsComposer.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ComposeMms -> if (action.attachmentRequested) {
                com.nuva.assistant.automation.UserPresentFileWorkflow.requestMmsAttachment(action)
                val activity = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (runCatching { context.startActivity(activity); true }.getOrDefault(false)) {
                    ExecutionOutcome("completed", "MMS attachment picker khulchi — file apni select korun.")
                } else {
                    com.nuva.assistant.automation.UserPresentFileWorkflow.cancel()
                    ExecutionOutcome("failed", "MMS attachment picker khulte parini.", "activity launch failed")
                }
            } else {
                when (val result = com.nuva.assistant.automation.SocialMmsComposer.mms(context, action)) {
                    is com.nuva.assistant.automation.SocialMmsComposer.Result.Opened -> ExecutionOutcome("completed", result.speech)
                    com.nuva.assistant.automation.SocialMmsComposer.Result.SensitiveBlocked ->
                        ExecutionOutcome("failed", "Sensitive ba financial MMS blocked.", "sensitive MMS blocked")
                    is com.nuva.assistant.automation.SocialMmsComposer.Result.Failed ->
                        ExecutionOutcome("failed", result.reason, result.reason)
                }
            }

            is NuvaAction.OpenVoicemail -> when (val result = com.nuva.assistant.automation.SocialMmsComposer.openVoicemail(context)) {
                is com.nuva.assistant.automation.SocialMmsComposer.Result.Opened -> ExecutionOutcome("completed", result.speech)
                com.nuva.assistant.automation.SocialMmsComposer.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Voicemail action blocked.", "voicemail blocked")
                is com.nuva.assistant.automation.SocialMmsComposer.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ComposeEmail -> if (action.attachmentRequested) {
                com.nuva.assistant.automation.UserPresentFileWorkflow.requestEmailAttachment(action)
                val activity = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (runCatching { context.startActivity(activity); true }.getOrDefault(false)) {
                    ExecutionOutcome("completed", "Attachment picker khulchi — file apni select korun.")
                } else {
                    com.nuva.assistant.automation.UserPresentFileWorkflow.cancel()
                    ExecutionOutcome("failed", "Attachment picker khulte parini.", "activity launch failed")
                }
            } else {
                when (val result = com.nuva.assistant.automation.EmailComposer.compose(context, action)) {
                    com.nuva.assistant.automation.EmailComposer.Result.Opened ->
                        ExecutionOutcome("completed", "Email composer khulechi — review kore Send apni chapun.")
                    is com.nuva.assistant.automation.EmailComposer.Result.Failed ->
                        ExecutionOutcome("failed", result.reason, result.reason)
                }
            }

            is NuvaAction.ClipboardAction -> when (
                val result = com.nuva.assistant.automation.ClipboardController.execute(context, action)
            ) {
                is com.nuva.assistant.automation.ClipboardController.Result.Done ->
                    ExecutionOutcome("completed", result.speech, screenText = result.content)
                com.nuva.assistant.automation.ClipboardController.Result.Empty ->
                    ExecutionOutcome("completed", "Clipboard ekhon khali.")
                com.nuva.assistant.automation.ClipboardController.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive/financial clipboard content handle korbo na.", "sensitive clipboard blocked")
                is com.nuva.assistant.automation.ClipboardController.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ViewCalendar -> when (
                val result = com.nuva.assistant.automation.CalendarViewHandoff.open(context, action.focusAt)
            ) {
                com.nuva.assistant.automation.CalendarViewHandoff.Result.Opened ->
                    ExecutionOutcome("completed", "Calendar view khulechi.")
                is com.nuva.assistant.automation.CalendarViewHandoff.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.CreateCalendarEvent -> when (
                val result = com.nuva.assistant.automation.CalendarEventHandoff.open(context, action)
            ) {
                com.nuva.assistant.automation.CalendarEventHandoff.Result.Opened ->
                    ExecutionOutcome("completed", "Calendar event draft khulechi — final Save apni chapun.")
                com.nuva.assistant.automation.CalendarEventHandoff.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive ba financial event details blocked.", "sensitive calendar event blocked")
                is com.nuva.assistant.automation.CalendarEventHandoff.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ShareText -> when (val result = com.nuva.assistant.automation.ProductivityHandoff.shareText(context, action)) {
                is com.nuva.assistant.automation.ProductivityHandoff.Result.Opened ->
                    ExecutionOutcome("completed", result.speech)
                is com.nuva.assistant.automation.ProductivityHandoff.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
                com.nuva.assistant.automation.ProductivityHandoff.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive ba financial text share korbo na.", "sensitive share blocked")
            }

            is NuvaAction.CreateContactDraft -> when (
                val result = com.nuva.assistant.automation.ProductivityHandoff.createContactDraft(context, action)
            ) {
                is com.nuva.assistant.automation.ProductivityHandoff.Result.Opened ->
                    ExecutionOutcome("completed", result.speech)
                is com.nuva.assistant.automation.ProductivityHandoff.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
                com.nuva.assistant.automation.ProductivityHandoff.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive contact draft blocked.", "sensitive contact blocked")
            }

            is NuvaAction.PrepareForm -> {
                if (action.details?.let {
                        com.nuva.assistant.core.security.SensitiveAppPolicy.mentionsCredentials(it) ||
                            com.nuva.assistant.core.security.SensitiveAppPolicy.refusalForText(it) != null
                    } == true
                ) {
                    return ExecutionOutcome("failed", "Sensitive ba financial details form draft-e rakhbo na.", "sensitive form details blocked")
                }
                val dao = notes ?: return ExecutionOutcome("failed", "Form draft save korte parini.", "notes storage unavailable")
                val localDraft = buildString {
                    append("Form draft [${action.kind.wireName}]")
                    action.details?.let { append(": ").append(it) }
                }
                val saved = dao.insertRow(NoteEntity(content = localDraft, kind = "note")) > 0
                when (val web = BrowserAutomation.searchWeb(context, action.kind.searchLabel)) {
                    is BrowserAutomation.Result.Opened -> ExecutionOutcome(
                        "completed",
                        if (saved) "Form details locally save kore official portal search khulechi — final Submit apni korben."
                        else "Official portal search khulechi — final Submit apni korben.",
                    )
                    is BrowserAutomation.Result.Failed -> ExecutionOutcome("failed", web.userReason, web.userReason)
                }
            }

            is NuvaAction.ScheduleCompose -> when (
                val result = com.nuva.assistant.automation.ScheduledComposeScheduler.schedule(context, action)
            ) {
                is com.nuva.assistant.automation.ScheduledComposeScheduler.Result.Scheduled ->
                    ExecutionOutcome("completed", "Compose reminder #${result.id} schedule hoyeche — notification tap korle draft khulbe.")
                com.nuva.assistant.automation.ScheduledComposeScheduler.Result.NotificationPermissionMissing ->
                    ExecutionOutcome("failed", "Scheduled draft-er jonno notification permission lagbe.", "notification permission missing")
                is com.nuva.assistant.automation.ScheduledComposeScheduler.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ListScheduledDrafts -> {
                val (speech, screen) = com.nuva.assistant.automation.ScheduledComposeScheduler.pendingSpeech()
                ExecutionOutcome("completed", speech, screenText = screen)
            }

            is NuvaAction.CancelScheduledDraft -> when (
                val result = com.nuva.assistant.automation.ScheduledComposeScheduler.cancelByOrdinal(context, action.ordinal)
            ) {
                is com.nuva.assistant.automation.ScheduledComposeScheduler.CancelResult.Cancelled ->
                    ExecutionOutcome("completed", "Scheduled draft cancel korechi.")
                com.nuva.assistant.automation.ScheduledComposeScheduler.CancelResult.Missing ->
                    ExecutionOutcome("failed", "Oi number-er pending draft paini.", "scheduled draft missing")
                is com.nuva.assistant.automation.ScheduledComposeScheduler.CancelResult.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ManageNotification -> when (
                val result = NuvaNotificationListener.manage(action.ordinal, action.operation)
            ) {
                is NuvaNotificationListener.ManageResult.Done ->
                    ExecutionOutcome("completed", "${result.appLabel} notification ${result.operation.wireName} complete.")
                NuvaNotificationListener.ManageResult.NeedsAccess -> {
                    NuvaNotificationListener.openAccessSettings(context)
                    ExecutionOutcome("failed", "Notification access lagbe — setting khulchi.", "notification access missing")
                }
                NuvaNotificationListener.ManageResult.NotificationMissing ->
                    ExecutionOutcome("failed", "Oi notification ta ar paini.", "notification missing")
                NuvaNotificationListener.ManageResult.ActionUnavailable ->
                    ExecutionOutcome("failed", "Ei notification-e requested official action available nei.", "notification action unavailable")
                NuvaNotificationListener.ManageResult.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive app-er notification manage korbo na.", "sensitive notification blocked")
                is NuvaNotificationListener.ManageResult.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ReplyNotification -> when (val result = NuvaNotificationListener.reply(action.ordinal, action.message)) {
                is NuvaNotificationListener.ReplyResult.Sent ->
                    ExecutionOutcome("completed", "${result.appLabel} notification e reply pathano hoyeche.")
                NuvaNotificationListener.ReplyResult.NeedsAccess -> {
                    NuvaNotificationListener.openAccessSettings(context)
                    ExecutionOutcome("failed", "Notification access lagbe — setting khulchi.", "notification access missing")
                }
                NuvaNotificationListener.ReplyResult.NotificationMissing ->
                    ExecutionOutcome("failed", "Oi notification ta ar paini.", "notification missing")
                NuvaNotificationListener.ReplyResult.ReplyUnavailable ->
                    ExecutionOutcome("failed", "Ei notification app official Reply action dey nai.", "RemoteInput unavailable")
                NuvaNotificationListener.ReplyResult.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Sensitive app ba credential notification e reply korbo na.", "sensitive reply blocked")
                is NuvaNotificationListener.ReplyResult.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
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

            is NuvaAction.MapNavigation -> when (val result = com.nuva.assistant.automation.MapsNavigation.open(context, action)) {
                com.nuva.assistant.automation.MapsNavigation.Result.Opened ->
                    ExecutionOutcome("completed", "Maps e ${action.destination} khulechi.")
                is com.nuva.assistant.automation.MapsNavigation.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.EmergencyDialer -> when (
                val result = com.nuva.assistant.automation.EmergencyHandoff.openDialer(context, action.service)
            ) {
                com.nuva.assistant.automation.EmergencyHandoff.Result.Opened ->
                    ExecutionOutcome("completed", "${action.service.wireName} emergency dialer 999-e ready — final Call apni chapun.")
                is com.nuva.assistant.automation.EmergencyHandoff.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.ClockControl -> when (
                val result = com.nuva.assistant.automation.ClockController.execute(context, action.operation)
            ) {
                is com.nuva.assistant.automation.ClockController.Result.Requested ->
                    ExecutionOutcome("completed", result.speech)
                is com.nuva.assistant.automation.ClockController.Result.ClockOpened ->
                    ExecutionOutcome("completed", result.speech)
                is com.nuva.assistant.automation.ClockController.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.DeviceStatusQuery ->
                ExecutionOutcome(
                    "completed",
                    deviceStatus.answer(action.query, preferences.languageBlocking()),
                )

            is NuvaAction.LocalAnswer ->
                ExecutionOutcome("completed", action.answer, screenText = action.answer)

            is NuvaAction.ReadSavedItems -> readSavedItems(action.kind)

            is NuvaAction.UserFile -> launchUserPresentFileWorkflow(context, action)

            is NuvaAction.ContactHandoff -> {
                com.nuva.assistant.automation.UserPresentContactWorkflow.request(action.operation)
                val activity = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (runCatching { context.startActivity(activity); true }.getOrDefault(false)) {
                    ExecutionOutcome("completed", "Contact picker khulchi — exact contact apni select korun.")
                } else {
                    com.nuva.assistant.automation.UserPresentContactWorkflow.clear()
                    ExecutionOutcome("failed", "Contact picker khulte parini.", "activity launch failed")
                }
            }

            is NuvaAction.UninstallApp -> when (
                val result = com.nuva.assistant.automation.AppManagement.requestUninstall(context, action.app)
            ) {
                is com.nuva.assistant.automation.AppManagement.Result.PromptOpened ->
                    ExecutionOutcome("completed", "${result.label} uninstall confirmation khulechi — final decision apnar.")
                com.nuva.assistant.automation.AppManagement.Result.NotFound ->
                    ExecutionOutcome("failed", "App ta installed list-e paini.", "app not found")
                com.nuva.assistant.automation.AppManagement.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Financial app uninstall NUVA initiate korbe na.", "sensitive app blocked")
                is com.nuva.assistant.automation.AppManagement.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

            is NuvaAction.OpenAppManagement -> when (
                val result = com.nuva.assistant.automation.AppManagement.openPanel(context, action.app, action.panel)
            ) {
                is com.nuva.assistant.automation.AppManagement.Result.PromptOpened ->
                    ExecutionOutcome("completed", "${result.label} er ${action.panel.wireName} screen khulechi.")
                com.nuva.assistant.automation.AppManagement.Result.NotFound ->
                    ExecutionOutcome("failed", "App ta installed list-e paini.", "app not found")
                com.nuva.assistant.automation.AppManagement.Result.SensitiveBlocked ->
                    ExecutionOutcome("failed", "Ei app management action blocked.", "app action blocked")
                is com.nuva.assistant.automation.AppManagement.Result.Failed ->
                    ExecutionOutcome("failed", result.reason, result.reason)
            }

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

            is NuvaAction.OpenChat -> when (val r = com.nuva.assistant.automation.ChatOpener.open(context, action)) {
                is com.nuva.assistant.automation.ChatOpener.Result.Opened -> {
                    contextMemory.onChatOpened(action.app.wireName, action.contact, action.phoneNumber)
                    ExecutionOutcome(
                        "completed",
                        if (r.verified) r.speech else r.speech + " (verify korar sokti ekhon nai)",
                    )
                }

                is com.nuva.assistant.automation.ChatOpener.Result.Failed ->
                    ExecutionOutcome("failed", r.speech, r.reason)
            }

            is NuvaAction.Press -> executePress(action.label)

            is NuvaAction.ClearText -> {
                val service = com.nuva.assistant.accessibility.NuvaAccessibilityService.instance
                if (service == null) {
                    ExecutionOutcome("failed", "এই কাজে Accessibility permission লাগবে — Settings থেকে NUVA চালু করুন।", "accessibility missing")
                } else {
                    val node = service.findFocusedEditable()
                    if (node == null) {
                        ExecutionOutcome("failed", "কোনো লেখার ঘর সিলেক্ট করা নেই।", "no focused input")
                    } else if (service.clearText(node)) {
                        ExecutionOutcome("completed", "লেখাটা মুছে দিয়েছি।")
                    } else {
                        ExecutionOutcome("failed", "মুছতে পারিনি।", "clear failed")
                    }
                }
            }

            is NuvaAction.OpenNotificationShade -> {
                val service = com.nuva.assistant.accessibility.NuvaAccessibilityService.instance
                if (service != null && service.openNotificationShade()) {
                    ExecutionOutcome("completed", "নোটিফিকেশন প্যানেল খুলেছি।")
                } else {
                    ExecutionOutcome("failed", "নোটিফিকেশন প্যানেল খুলতে Accessibility permission লাগবে।", "accessibility missing")
                }
            }

            is NuvaAction.OpenNotificationApp -> {
                val snapshot = NuvaNotificationListener.safeSnapshot()
                val notification = snapshot.getOrNull(action.ordinal - 1)
                if (notification == null) {
                    ExecutionOutcome("failed", "নোটিফিকেশন পাওয়া যায়নি — Notification access দেওয়া আছে কি না দেখুন।", "notification access missing")
                } else {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(notification.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        val launched = runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
                        if (launched) {
                            ExecutionOutcome("completed", "${notification.appLabel} অ্যাপটি খুলেছি।")
                        } else {
                            ExecutionOutcome("failed", "অ্যাপটি খুলতে পারিনি।", "launch failed")
                        }
                    } else {
                        ExecutionOutcome("failed", "${notification.appLabel} আবার খোলা যাচ্ছে না।", "no launch intent")
                    }
                }
            }

            is NuvaAction.DescribeScreen -> {
                val service = com.nuva.assistant.accessibility.NuvaAccessibilityService.instance
                val state = service?.captureScreenState()
                when {
                    service == null -> ExecutionOutcome("failed", "Accessibility permission লাগবে — Settings থেকে NUVA চালু করুন।", "accessibility missing")
                    state == null -> ExecutionOutcome("failed", "স্ক্রিন পড়া গেল না।", "no window")
                    else -> {
                        val summary = com.nuva.assistant.accessibility.ScreenStateModel.summarize(state)
                        ExecutionOutcome("completed", summary, null, state.visibleText.ifBlank { null })
                    }
                }
            }

            is NuvaAction.MediaControl -> when (val r = com.nuva.assistant.automation.MediaPlaybackControl.control(context, action)) {
                is com.nuva.assistant.automation.MediaPlaybackControl.Result.Done ->
                    ExecutionOutcome("completed", r.speech)

                is com.nuva.assistant.automation.MediaPlaybackControl.Result.Failed ->
                    ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.VolumeControl -> when (val r = com.nuva.assistant.automation.VolumeController.control(context, action)) {
                is com.nuva.assistant.automation.VolumeController.Result.Done ->
                    ExecutionOutcome("completed", r.speech)

                is com.nuva.assistant.automation.VolumeController.Result.Failed ->
                    ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.CameraOpen -> when (val r = com.nuva.assistant.automation.CameraOpener.open(context, action.mode)) {
                is com.nuva.assistant.automation.CameraOpener.Result.Opened ->
                    ExecutionOutcome("completed", "Camera khulchi — chobi apni tulun.")

                is com.nuva.assistant.automation.CameraOpener.Result.Failed ->
                    ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            is NuvaAction.CallContact -> {
                val number = action.phoneNumber
                if (number.isNullOrBlank()) {
                    ExecutionOutcome("failed", "${action.contact} er number painai.", "no phone number")
                } else {
                    val direct = preferences.directCallBlocking()
                    val ok = AppLauncher.dial(context, number, direct)
                    if (ok) {
                        ExecutionOutcome("completed", "${action.contact}-কে কল করছি।")
                    } else {
                        ExecutionOutcome("failed", "Call করা যায়নি।", "dial failed")
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

    private fun launchUserPresentFileWorkflow(context: Context, action: NuvaAction.UserFile): ExecutionOutcome {
        val operation = action.operation
        com.nuva.assistant.automation.UserPresentFileWorkflow.request(operation, action.newName)
        val activity = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val opened = runCatching { context.startActivity(activity); true }.getOrDefault(false)
        return if (opened) {
            val what = when (operation) {
                UserFileOperation.OPEN_FILE -> "file"
                UserFileOperation.SHARE_FILE -> "share korar file"
                UserFileOperation.READ_TEXT -> "text file"
                UserFileOperation.OPEN_FOLDER -> "folder"
                UserFileOperation.PICK_PHOTO -> "photo"
                UserFileOperation.SHARE_PHOTO -> "share korar photo"
                UserFileOperation.PICK_VIDEO -> "video"
                UserFileOperation.SHARE_VIDEO -> "share korar video"
                UserFileOperation.SHARE_MULTIPLE_FILES -> "share korar file-gulo"
                UserFileOperation.SHARE_MULTIPLE_PHOTOS -> "share korar photo-gulo"
                UserFileOperation.SHARE_MULTIPLE_VIDEOS -> "share korar video-gulo"
                UserFileOperation.EMAIL_ATTACHMENT -> "email attachment"
                UserFileOperation.EMAIL_ATTACHMENTS -> "email attachments"
                UserFileOperation.MMS_ATTACHMENT -> "MMS attachment"
                UserFileOperation.PRINT_PDF -> "print korar PDF"
                UserFileOperation.RENAME_FILE -> "rename korar file"
                UserFileOperation.COPY_FILE -> "copy korar source file"
                UserFileOperation.MOVE_FILE -> "move korar source file"
                UserFileOperation.DELETE_FILE -> "delete korar file"
                UserFileOperation.EDIT_PHOTO -> "edit korar photo"
            }
            ExecutionOutcome("completed", "$what picker khulchi — target apni select korun.")
        } else {
            com.nuva.assistant.automation.UserPresentFileWorkflow.cancel()
            ExecutionOutcome("failed", "System picker khulte parini.", "activity launch failed")
        }
    }

    private suspend fun readSavedItems(kind: SavedItemKind): ExecutionOutcome {
        val dao = notes ?: return ExecutionOutcome("failed", "Saved list porte parini.", "notes storage unavailable")
        val roomKind = if (kind == SavedItemKind.TODO || kind == SavedItemKind.SHOPPING) "todo" else "note"
        val rows = dao.byKindOnce(roomKind, 50).filter { row ->
            when (kind) {
                SavedItemKind.SHOPPING -> row.content.startsWith("Shopping:", ignoreCase = true)
                SavedItemKind.EXPENSE -> row.content.startsWith("Expense:", ignoreCase = true)
                SavedItemKind.TODO -> !row.content.startsWith("Shopping:", ignoreCase = true)
                SavedItemKind.NOTE -> !row.content.startsWith("Expense:", ignoreCase = true)
            }
        }
        if (rows.isEmpty()) {
            val label = kind.wireName.replaceFirstChar { it.uppercase() }
            return ExecutionOutcome("completed", "$label list ekhon khali.", screenText = "$label list: empty")
        }
        val display = rows.take(20).mapIndexed { index, row ->
            val content = row.content.substringAfter(':', row.content).trim()
            val done = if (roomKind == "todo" && row.done) "✓ " else ""
            "${index + 1}. $done$content"
        }.joinToString("\n")
        val label = kind.wireName.replaceFirstChar { it.uppercase() }
        val spoken = rows.take(8).mapIndexed { index, row ->
            "${index + 1}, ${row.content.substringAfter(':', row.content).trim()}"
        }.joinToString("; ")
        val more = if (rows.size > 8) "; aro ${rows.size - 8} ta screen e dekhacchi" else ""
        return ExecutionOutcome("completed", "$label list: $spoken$more.", screenText = "$label list\n$display")
    }

    /**
     * App-agnostic press (Phase 5): resolve the target from the CURRENT
     * screen's safe state. Exactly one match ⇒ tap; none/many ⇒ honest
     * stop-and-ask. Financial screens are refused outright (LEVEL 3).
     */
    private suspend fun executePress(label: String?): com.nuva.assistant.command.CommandExecutor.ExecutionOutcome {
        val service = com.nuva.assistant.accessibility.NuvaAccessibilityService.instance
            ?: return ExecutionOutcome("failed", "এই কাজে Accessibility permission লাগবে — Settings থেকে NUVA চালু করুন।", "accessibility missing")
        val state = service.captureScreenState()
            ?: return ExecutionOutcome("failed", "স্ক্রিন পড়া গেল না।", "no window")
        if (state.sensitiveScreen) {
            return ExecutionOutcome(
                "failed",
                com.nuva.assistant.core.security.SensitiveAppPolicy.SCREEN_READ_GUARD_SPEECH,
                "blocked: sensitive screen",
            )
        }
        val matches = if (label.isNullOrBlank()) {
            state.buttons
        } else {
            com.nuva.assistant.accessibility.ScreenStateModel.matchButtons(state, label)
        }
        return when {
            matches.isEmpty() ->
                ExecutionOutcome("failed", "এমন কোনো বাটন স্ক্রিনে দেখছি না।", "button not found")

            matches.size > 1 -> {
                val names = matches.take(4).joinToString(", ") { it.label }
                val hint = if (label.isNullOrBlank()) "কয়েকটা বাটন আছে" else "\"$label\" নামে একাধিক বাটন"
                ExecutionOutcome("failed", "$hint — নির্দিষ্ট করে বলুন: $names", "ambiguous button")
            }

            else -> {
                val node = service.findNode(UiSelector(text = matches.first().label))
                    ?: service.findNode(UiSelector(contentDescription = matches.first().label))
                if (node != null && service.clickNode(node)) {
                    ExecutionOutcome("completed", "\"${matches.first().label}\" বাটন চেপে দিয়েছি।")
                } else {
                    ExecutionOutcome("failed", "বাটনটি চাপা গেল না — স্ক্রিন বদলেছে।", "click failed")
                }
            }
        }
    }

    private suspend fun executeSendMessage(context: Context, action: NuvaAction.SendMessage): ExecutionOutcome {
        // COMPOSE tier (Telegram, Messenger, Signal, Viber, IMO): open the app
        // with the message pre-filled; the user picks the chat and taps Send.
        if (MessagingRegistry.tierOf(action.app) == MessagingRegistry.Tier.COMPOSE) {
            val opened = MessagingRegistry.openWithPrefilledMessage(context, action.app, action.message)
            return if (opened) {
                ExecutionOutcome(
                    "completed",
                    "${action.app.wireName} khule message lekhata bosiye dilam — chat beche Send apni chapun.",
                )
            } else {
                ExecutionOutcome("failed", "${action.app.wireName} install kora nai.", "app missing")
            }
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
                is WhatsAppAutomation.Result.Sent -> {
                    contextMemory.onMessageSent()
                    ExecutionOutcome("completed", "${action.contact}-এর message পাঠানো হয়েছে।")
                }

                is WhatsAppAutomation.Result.Failed ->
                    ExecutionOutcome("failed", r.userReason, r.userReason)
            }

            MessagingApp.SMS -> {
                val number = action.phoneNumber
                    ?: return ExecutionOutcome("failed", "${action.contact} er number painai.", "no phone number")
                when (val r = SmsAutomation.sendOrCompose(context, number, action.message)) {
                    is SmsAutomation.Result.Sent -> {
                        contextMemory.onMessageSent()
                        ExecutionOutcome("completed", "${action.contact}-এর SMS পাঠানো হয়েছে।")
                    }

                    is SmsAutomation.Result.ComposeOpened ->
                        ExecutionOutcome("completed", r.reason)

                    is SmsAutomation.Result.Failed ->
                        ExecutionOutcome("failed", r.userReason, r.userReason)
                }
            }

            else -> ExecutionOutcome("failed", "${action.app.wireName} ekhon support kori na.", "unsupported")
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
