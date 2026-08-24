/**
 * ACTION VALIDATOR — step 6 of the golden pipeline (§30), implementing §7/§10.
 *
 * Turns raw model text into either a validated action or a safe refusal. Nothing
 * from the model is trusted:
 *   * non-JSON / truncated JSON        → AI_INVALID_OUTPUT
 *   * unknown action type              → UNSUPPORTED (safe response, §8)
 *   * known type with bad parameters   → UNSUPPORTED with the validator's reason
 *   * unknown extra keys               → rejected by the strict schemas
 */
import { z } from 'zod';
import { actionSchema, isRegisteredActionType, safeText, type ParsedAction } from './actions.js';
import { packageHintFor } from './apps.js';
import { RISK_LEVELS, type RiskLevel } from '../types/action.js';

export type ValidationOutcome =
  | {
      ok: true;
      action: ParsedAction;
      modelRisk?: RiskLevel;
      modelRequiresConfirmation?: boolean;
      confidence?: number;
      speech?: string;
    }
  | { ok: false; kind: 'unsupported' | 'invalid'; reasons: string[] };

/**
 * Extracts the first balanced JSON object from model output. Handles ```json
 * fences and leading/trailing prose that some models still emit despite
 * response_format: json_object.
 */
export function extractJsonObject(raw: string): string | null {
  const withoutFences = raw.replace(/```(?:json)?/gi, ' ');
  const start = withoutFences.indexOf('{');
  if (start === -1) return null;

  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let i = start; i < withoutFences.length; i += 1) {
    const char = withoutFences[i];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (char === '\\') {
      if (inString) escaped = true;
      continue;
    }
    if (char === '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (char === '{') depth += 1;
    else if (char === '}') {
      depth -= 1;
      if (depth === 0) return withoutFences.slice(start, i + 1);
    }
  }
  return null;
}

/** Lenient outer envelope — strictness is applied to `action` itself. */
const envelopeSchema = z
  .object({
    intent: z.string().max(64).optional(),
    action: z.unknown().optional(),
    risk: z.string().max(16).optional(),
    requires_confirmation: z.union([z.boolean(), z.string()]).optional(),
    confidence: z.union([z.number(), z.string()]).optional(),
    speech: safeText(400).optional(),
    reason: safeText(400).optional(),
  })
  .passthrough();

function coerceRisk(value: string | undefined): RiskLevel | undefined {
  if (!value) return undefined;
  const normalized = value.trim().toLowerCase();
  return (RISK_LEVELS as readonly string[]).includes(normalized) ? (normalized as RiskLevel) : undefined;
}

function coerceBoolean(value: boolean | string | undefined): boolean | undefined {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase();
    if (['true', 'yes', '1'].includes(normalized)) return true;
    if (['false', 'no', '0'].includes(normalized)) return false;
  }
  return undefined;
}

function coerceConfidence(value: number | string | undefined): number | undefined {
  const numeric = typeof value === 'string' ? Number.parseFloat(value) : value;
  if (typeof numeric !== 'number' || !Number.isFinite(numeric)) return undefined;
  return Math.min(1, Math.max(0, numeric));
}

/** Adds the Android package hint for app actions when we know it. */
function enrich(action: ParsedAction): ParsedAction {
  if ((action.type === 'OPEN_APP' || action.type === 'CLOSE_APP') && action.package === undefined) {
    const hint = packageHintFor(action.app);
    if (hint) return { ...action, package: hint };
  }
  return action;
}

/** Validates an already-parsed action object (also used by the fallback parser). */
export function validateAction(candidate: unknown): ValidationOutcome {
  if (candidate === null || typeof candidate !== 'object' || Array.isArray(candidate)) {
    return { ok: false, kind: 'invalid', reasons: ['action must be a JSON object'] };
  }

  const type = (candidate as { type?: unknown }).type;
  if (!isRegisteredActionType(type)) {
    return {
      ok: false,
      kind: 'unsupported',
      reasons: [`action type ${JSON.stringify(type)} is not in the NUVA action registry`],
    };
  }

  const parsed = actionSchema.safeParse(candidate);
  if (!parsed.success) {
    const reasons = parsed.error.issues
      .slice(0, 5)
      .map((issue) => `${issue.path.join('.') || type}: ${issue.message}`);
    return { ok: false, kind: 'unsupported', reasons };
  }

  return { ok: true, action: enrich(parsed.data) };
}

/** Validates a full model response (raw text or already-parsed object). */
export function validateModelOutput(raw: string | unknown): ValidationOutcome {
  let candidate: unknown = raw;

  if (typeof raw === 'string') {
    const json = extractJsonObject(raw);
    if (json === null) {
      return { ok: false, kind: 'invalid', reasons: ['model output contained no JSON object'] };
    }
    try {
      candidate = JSON.parse(json);
    } catch (err) {
      return {
        ok: false,
        kind: 'invalid',
        reasons: [`model output was not valid JSON: ${err instanceof Error ? err.message : 'parse error'}`],
      };
    }
  }

  const envelope = envelopeSchema.safeParse(candidate);
  if (!envelope.success) {
    return { ok: false, kind: 'invalid', reasons: ['model output did not match the response envelope'] };
  }

  const { intent, action, risk, requires_confirmation, confidence, speech, reason } = envelope.data;

  // Explicit refusal from the model.
  const declaredUnsupported = intent?.trim().toUpperCase() === 'UNSUPPORTED';
  if (declaredUnsupported || action === null || action === undefined) {
    return {
      ok: false,
      kind: declaredUnsupported ? 'unsupported' : 'invalid',
      reasons: [reason ?? (declaredUnsupported ? 'model reported the request as unsupported' : 'model returned no action')],
    };
  }

  const validated = validateAction(action);
  if (!validated.ok) return validated;

  const outcome: ValidationOutcome = {
    ok: true,
    action: validated.action,
  };
  const modelRisk = coerceRisk(risk);
  if (modelRisk) outcome.modelRisk = modelRisk;
  const confirm = coerceBoolean(requires_confirmation);
  if (confirm !== undefined) outcome.modelRequiresConfirmation = confirm;
  const score = coerceConfidence(confidence);
  if (score !== undefined) outcome.confidence = score;
  if (speech) outcome.speech = speech;
  return outcome;
}
