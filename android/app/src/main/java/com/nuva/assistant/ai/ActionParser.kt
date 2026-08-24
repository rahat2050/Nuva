package com.nuva.assistant.ai

import com.nuva.assistant.command.CommandDecision
import com.nuva.assistant.command.CommandValidator
import com.nuva.assistant.command.NuvaIntent
import com.nuva.assistant.command.NuvaRisk

/**
 * Turns the raw server response into a locally re-validated [CommandDecision].
 *
 * Defence in depth: even though the server already validated the action, the
 * client re-runs the full whitelist + schema checks. A decision whose action
 * fails local validation becomes UNSUPPORTED — never executed.
 */
object ActionParser {

    fun parse(response: CommandResponseDto): CommandDecision {
        val result = response.result
        if (result == null) {
            return unsupported(
                speech = response.error?.speech ?: "Bujhte parini.",
                reasons = listOf("server returned no result"),
                source = response.meta?.source ?: "unknown",
                commandId = response.meta?.commandId,
                modelRisk = NuvaRisk.LOW,
            )
        }

        val intent = NuvaIntent.fromWire(result.intent)
        val actionJson = result.action

        if (intent == null || actionJson == null) {
            // UNSUPPORTED (or malformed intent) — never executed, speech is spoken.
            val validated = CommandValidator.validateAction(actionJson)
            val reasons = result.reasons.ifEmpty { listOf("intent ${result.intent} is not executable") }
            val risk = CommandValidator.recomputeRisk(
                action = (validated as? CommandValidator.ValidatedAction.Valid)?.action,
                unsupportedReasonText = reasons.joinToString(" ") + " " + (result.speech ?: ""),
                modelRisk = NuvaRisk.fromWire(result.risk),
            )
            return CommandDecision(
                intent = null,
                action = null,
                unsupported = true,
                risk = risk,
                requiresConfirmation = false,
                speech = result.speech ?: "Eta ami korte pari na.",
                reasons = reasons,
                commandId = response.meta?.commandId,
                source = response.meta?.source ?: "groq",
            )
        }

        return when (val validated = CommandValidator.validateAction(actionJson)) {
            is CommandValidator.ValidatedAction.Invalid -> CommandDecision(
                intent = null,
                action = null,
                unsupported = true,
                risk = CommandValidator.recomputeRisk(
                    action = null,
                    unsupportedReasonText = validated.reasons.joinToString(" "),
                    modelRisk = NuvaRisk.fromWire(result.risk),
                ),
                requiresConfirmation = false,
                speech = "Ai command ta validate korte parini.",
                reasons = validated.reasons,
                commandId = response.meta?.commandId,
                source = response.meta?.source ?: "groq",
            )

            is CommandValidator.ValidatedAction.Valid -> {
                val modelRisk = NuvaRisk.fromWire(result.risk)
                val risk = CommandValidator.recomputeRisk(validated.action, "", modelRisk)
                CommandDecision(
                    intent = intent,
                    action = validated.action,
                    unsupported = false,
                    risk = risk,
                    requiresConfirmation = CommandValidator.requiresConfirmation(
                        risk,
                        result.requiresConfirmation ?: false,
                    ),
                    speech = result.speech ?: "",
                    reasons = result.reasons,
                    commandId = response.meta?.commandId,
                    source = response.meta?.source ?: "groq",
                )
            }
        }
    }

    private fun unsupported(
        speech: String,
        reasons: List<String>,
        source: String,
        commandId: String?,
        modelRisk: NuvaRisk,
    ): CommandDecision = CommandDecision(
        intent = null,
        action = null,
        unsupported = true,
        risk = CommandValidator.recomputeRisk(null, reasons.joinToString(" "), modelRisk),
        requiresConfirmation = false,
        speech = speech,
        reasons = reasons,
        commandId = commandId,
        source = source,
    )
}
