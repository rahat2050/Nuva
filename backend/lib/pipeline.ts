/**
 * The NUVA pipeline (§30 — the golden rule), server-side half:
 *
 *   TEXT NORMALIZATION → AI UNDERSTANDING → STRUCTURED ACTION → VALIDATION
 *   → RISK CHECK → (confirmation flag) → HISTORY UPDATE → VOICE RESPONSE text
 *
 * The remaining steps (CONFIRMATION, EXECUTION, RESULT VERIFICATION) belong to
 * the Android app in PHRASE 2. This module never executes anything.
 */
import { assessRisk } from './risk.js';
import { isSensitiveApp } from './apps.js';
import { buildUserMessage, SYSTEM_PROMPT } from './prompt.js';
import { groqChatJson } from './groq.js';
import { parseFallback } from './fallbackParser.js';
import { normalizeCommand } from './normalize.js';
import { validateAction, validateModelOutput, type ValidationOutcome } from './validate.js';
import { confirmationPrompt, notUnderstoodSpeech, speechForAction, unsupportedSpeech } from './speech.js';
import { recordCommand, recordConversation } from './repository.js';
import { groqConfigured, type NuvaEnv } from './env.js';
import { NuvaError } from './errors.js';
import { containsCredentialTerms, containsTransactionRequest } from './sensitive.js';
import type { Logger } from './logger.js';
import type { Identity } from './auth.js';
import type { ParsedAction } from './actions.js';
import type { ActionDecision, CommandStatus, Language, RiskLevel } from '../types/action.js';
import { LANGUAGES } from '../types/action.js';
import type { CommandRequest, CommandResponse } from '../types/api.js';

export interface InterpretOptions {
  request: CommandRequest;
  identity: Identity;
  logger: Logger;
  requestId: string;
  env: NuvaEnv;
}

interface Interpretation {
  outcome: ValidationOutcome;
  source: 'groq' | 'fallback';
  model: string | null;
}

function resolveLanguage(hint: CommandRequest['language'], detected: Language): Language {
  if (hint && hint !== 'auto' && (LANGUAGES as readonly string[]).includes(hint)) return hint;
  return detected;
}

function credentialRefusal(language: Language): string {
  if (language === 'bn') return 'OTP, PIN, পাসওয়ার্ড, কার্ড/CVV বা অন্য গোপন তথ্য NUVA গ্রহণ করে না।';
  if (language === 'en') return 'NUVA does not handle OTPs, PINs, passwords, card/CVV data, or other secrets.';
  return 'NUVA OTP, PIN, password, card/CVV ba onno secret handle kore na.';
}

function transactionRefusal(language: Language): string {
  if (language === 'bn') return 'এই আর্থিক লেনদেন NUVA নিজে করবে না; আপনি চাইলে নিরাপদভাবে নিজে করতে পারেন।';
  if (language === 'en') return 'NUVA will not perform this financial transaction; you can complete it yourself.';
  return 'Ei financial transaction NUVA korbe na; apni chaile nije securely korte paren.';
}

function blockedPreflightResponse(options: {
  requestId: string;
  language: Language;
  wakeWordDetected: boolean;
  startedAt: number;
  inputMarker: string;
  speech: string;
  reason: string;
}): CommandResponse {
  return {
    ok: true,
    request_id: options.requestId,
    input: {
      text: options.inputMarker,
      normalized_text: options.inputMarker,
      language: options.language,
      wake_word_detected: options.wakeWordDetected,
    },
    result: {
      intent: 'UNSUPPORTED',
      action: null,
      risk: 'high',
      requires_confirmation: false,
      speech: options.speech,
      reasons: [options.reason],
    },
    meta: {
      source: 'fallback',
      model: null,
      latency_ms: Date.now() - options.startedAt,
      command_id: null,
      persisted: false,
    },
  };
}

/**
 * Ask the model, then fall back to the deterministic parser only if allowed.
 *
 * Failure contract, kept deliberately honest:
 *   * AI reachable but its answer was garbage → UNSUPPORTED at HTTP 200
 *     ("I couldn't understand that command") — the AI is up, this input failed.
 *   * AI unreachable or unconfigured          → the real upstream error
 *     ("I can't reach the NUVA server" / "not configured") so an outage is never
 *     disguised as a comprehension failure.
 */
async function interpret(
  normalizedText: string,
  language: Language,
  options: InterpretOptions,
): Promise<Interpretation> {
  const { env, logger, request } = options;

  /** @param onMiss error to throw when the parser cannot help (or is disabled) */
  const tryFallback = (reason: string, onMiss: NuvaError): Interpretation => {
    if (!env.allowFallbackParser) {
      logger.warn('fallback parser is disabled', { reason });
      throw onMiss;
    }
    const fallback = parseFallback(normalizedText);
    if (!fallback) {
      logger.warn('fallback parser found no match', { reason });
      throw onMiss;
    }
    logger.info('fallback parser matched', { rule: fallback.rule, reason });
    return { outcome: validateAction(fallback.action), source: 'fallback', model: null };
  };

  if (!groqConfigured(env)) {
    logger.warn('GROQ_API_KEY missing, using deterministic fallback parser');
    return tryFallback(
      'groq not configured',
      new NuvaError('NOT_CONFIGURED', 'GROQ_API_KEY is not set, so only simple built-in commands work'),
    );
  }

  const contextContainsCredentials = request.context
    ? containsCredentialTerms(JSON.stringify(request.context))
    : false;
  const contextIsFinancial = isSensitiveApp(request.context?.foreground_app ?? '');
  const safeContext = contextContainsCredentials || contextIsFinancial ? undefined : request.context;

  let completion;
  try {
    completion = await groqChatJson(
      { system: SYSTEM_PROMPT, user: buildUserMessage(normalizedText, language, safeContext) },
      logger,
      env,
    );
  } catch (err) {
    const error = err instanceof NuvaError ? err : new NuvaError('AI_UNAVAILABLE', 'Groq call failed');
    logger.warn('groq unavailable, attempting fallback parser', { code: error.code });
    // On a miss, surface the original upstream failure — not a fake "didn't understand".
    return tryFallback(`groq ${error.code}`, error);
  }

  const outcome = validateModelOutput(completion.content);

  // A deliberate model refusal is respected — we must NOT let the offline parser
  // execute something the AI declined (e.g. a money transfer phrased as an app tap).
  if (!outcome.ok && outcome.kind === 'invalid') {
    logger.warn('model produced invalid output, attempting fallback parser', { reasons: outcome.reasons });
    try {
      return tryFallback('invalid model output', new NuvaError('AI_INVALID_OUTPUT', 'unreachable'));
    } catch {
      // The AI is healthy, so answer "I couldn't understand that" at HTTP 200
      // rather than reporting a server fault.
      return { outcome, source: 'groq', model: completion.model };
    }
  }

  return { outcome, source: 'groq', model: completion.model };
}

function buildDecision(
  interpretation: Interpretation,
  language: Language,
  commandText: string,
): { decision: ActionDecision; status: CommandStatus; action: ParsedAction | null } {
  const { outcome } = interpretation;

  if (!outcome.ok) {
    // Risk is still assessed (with a null action) so a refused money request is
    // recorded as high risk for auditing.
    const assessment = assessRisk(null, { commandText });
    const decision: ActionDecision = {
      intent: 'UNSUPPORTED',
      action: null,
      risk: assessment.risk,
      // Nothing is pending, so there is nothing to confirm.
      requires_confirmation: false,
      speech: outcome.kind === 'unsupported' ? unsupportedSpeech(language) : notUnderstoodSpeech(language),
      reasons: outcome.reasons,
    };
    return { decision, status: 'unsupported', action: null };
  }

  // Re-check model-produced fields independently from the user transcript. A
  // model/backend regression must not inject a secret into TYPE_TEXT/message.
  if (containsCredentialTerms(JSON.stringify(outcome))) {
    const decision: ActionDecision = {
      intent: 'UNSUPPORTED',
      action: null,
      risk: 'high',
      requires_confirmation: false,
      speech: credentialRefusal(language),
      reasons: ['blocked: credential-bearing structured action'],
    };
    return { decision, status: 'unsupported', action: null };
  }
  if (containsTransactionRequest(JSON.stringify(outcome))) {
    const decision: ActionDecision = {
      intent: 'UNSUPPORTED',
      action: null,
      risk: 'high',
      requires_confirmation: false,
      speech: transactionRefusal(language),
      reasons: ['blocked: financial transaction structured action'],
    };
    return { decision, status: 'unsupported', action: null };
  }

  const assessment = assessRisk(outcome.action, {
    commandText,
    ...(outcome.modelRisk ? { modelRisk: outcome.modelRisk } : {}),
    ...(outcome.modelRequiresConfirmation !== undefined
      ? { modelRequiresConfirmation: outcome.modelRequiresConfirmation }
      : {}),
  });

  // When confirmation is required the reply must be a question, so a
  // declarative model sentence is replaced by the canonical prompt (§24).
  const modelSpeech = outcome.speech?.trim();
  const speech = assessment.requiresConfirmation
    ? modelSpeech && modelSpeech.endsWith('?')
      ? modelSpeech
      : confirmationPrompt(outcome.action, language)
    : (modelSpeech ?? speechForAction(outcome.action, language));

  const decision: ActionDecision = {
    intent: outcome.action.type,
    action: outcome.action,
    risk: assessment.risk,
    requires_confirmation: assessment.requiresConfirmation,
    speech,
  };
  if (outcome.confidence !== undefined) decision.confidence = outcome.confidence;
  if (assessment.reasons.length > 0) decision.reasons = assessment.reasons;

  return {
    decision,
    status: assessment.requiresConfirmation ? 'pending_confirmation' : 'ready',
    action: outcome.action,
  };
}

export async function interpretCommand(options: InterpretOptions): Promise<CommandResponse> {
  const { request, identity, logger, requestId, env } = options;
  const startedAt = Date.now();

  // 1. TEXT NORMALIZATION
  const normalized = normalizeCommand(request.text);
  const language = resolveLanguage(request.language, normalized.language);

  // Stop before Groq and persistence. Returning the original secret-bearing
  // transcript in `input` would unnecessarily retain it in another object, so
  // even the echo is replaced with a fixed marker.
  if (containsCredentialTerms(normalized.text)) {
    logger.warn('credential-bearing command refused before interpretation', {
      language,
      chars: normalized.text.length,
      has_user: identity.userId !== null,
    });
    return blockedPreflightResponse({
      requestId,
      language,
      wakeWordDetected: normalized.wakeWordDetected,
      startedAt,
      inputMarker: '[sensitive content hidden]',
      speech: credentialRefusal(language),
      reason: 'blocked: credential handling',
    });
  }

  if (containsTransactionRequest(normalized.text)) {
    logger.warn('financial transaction refused before interpretation', {
      language,
      chars: normalized.text.length,
      has_user: identity.userId !== null,
    });
    return blockedPreflightResponse({
      requestId,
      language,
      wakeWordDetected: normalized.wakeWordDetected,
      startedAt,
      inputMarker: '[financial request hidden]',
      speech: transactionRefusal(language),
      reason: 'blocked: financial transaction automation',
    });
  }

  logger.info('command received', {
    language,
    detected_language: normalized.language,
    wake_word: normalized.wakeWordDetected,
    chars: normalized.text.length,
    has_user: identity.userId !== null,
  });

  // 2-4. AI UNDERSTANDING → STRUCTURED ACTION → VALIDATION
  const interpretation = await interpret(normalized.normalized, language, options);

  // 5-6. RISK CHECK → CONFIRMATION FLAG
  const { decision, status, action } = buildDecision(interpretation, language, normalized.text);

  logger.info('decision made', {
    intent: decision.intent,
    risk: decision.risk,
    requires_confirmation: decision.requires_confirmation,
    source: interpretation.source,
    model: interpretation.model,
    reasons: decision.reasons,
  });

  // 7. MEMORY/HISTORY UPDATE — best effort, never blocks the response.
  let commandId: string | null = null;
  if (identity.userId !== null && env.persistEnabled) {
    const userId = identity.userId;
    const [id] = await Promise.all([
      recordCommand(
        {
          userId,
          command: normalized.text,
          intent: decision.intent,
          action,
          risk: decision.risk as RiskLevel,
          status,
        },
        logger,
        env,
      ),
      recordConversation({ userId, role: 'user', message: normalized.text }, logger, env),
      recordConversation({ userId, role: 'assistant', message: decision.speech }, logger, env),
    ]);
    commandId = id;
  }

  return {
    ok: true,
    request_id: requestId,
    input: {
      text: normalized.text,
      normalized_text: normalized.normalized,
      language,
      wake_word_detected: normalized.wakeWordDetected,
    },
    result: decision,
    meta: {
      source: interpretation.source,
      model: interpretation.model,
      latency_ms: Date.now() - startedAt,
      command_id: commandId,
      persisted: commandId !== null,
    },
  };
}
