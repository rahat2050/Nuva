/**
 * HTTP contract for the NUVA backend. Frozen in PHRASE 1 so the PHRASE 2
 * Android client can be written against it without churn.
 */
import type { ActionDecision, CommandStatus, Language, RiskLevel } from './action';

export interface ApiErrorBody {
  ok: false;
  request_id: string;
  error: {
    code: string;
    /** Developer-facing message. Safe to log, never contains secrets. */
    message: string;
    /** User-facing sentence suitable for TTS (§24). */
    speech: string;
    details?: unknown;
  };
}

export interface CommandRequest {
  /** Raw transcript from Android SpeechRecognizer, or typed text. */
  text: string;
  /** Hint only; the server always runs its own detection. */
  language?: Language | 'auto';
  /** Opaque client id used for rate limiting and diagnostics. */
  device_id?: string;
  /** Client-generated id for idempotency/correlation in logs. */
  client_request_id?: string;
  /** Optional screen context supplied by AccessibilityService in PHRASE 2. */
  context?: {
    foreground_app?: string;
    screen_summary?: string;
  };
}

export interface CommandResponse {
  ok: true;
  request_id: string;
  input: {
    text: string;
    normalized_text: string;
    language: Language;
    wake_word_detected: boolean;
  };
  result: ActionDecision;
  meta: {
    /** groq = real AI, fallback = deterministic offline parser. */
    source: 'groq' | 'fallback';
    model: string | null;
    latency_ms: number;
    /** Row id in `commands`, when persistence is enabled. */
    command_id: string | null;
    persisted: boolean;
  };
}

export interface HealthResponse {
  ok: boolean;
  service: 'nuva-backend';
  version: string;
  phase: 1;
  time: string;
  request_id: string;
  config: {
    groq: { configured: boolean; model: string; fallback_model: string };
    supabase: { configured: boolean; service_role: boolean };
    auth_required: boolean;
    persistence: boolean;
    fallback_parser: boolean;
  };
  /** Only present for ?deep=1 — performs real upstream round-trips. */
  checks?: {
    groq: DependencyCheck;
    supabase: DependencyCheck;
  };
}

export interface DependencyCheck {
  ok: boolean;
  status: 'ok' | 'not_configured' | 'error';
  latency_ms: number | null;
  detail?: string;
}

export interface CommandLogRequest {
  /** Row returned by /api/ai/command. Omit to create a new row. */
  command_id?: string;
  status: CommandStatus;
  error?: string;
  /** Required only when creating a new row. */
  command?: string;
  intent?: string;
  action?: unknown;
  risk?: RiskLevel;
}

export interface MemoryUpsertRequest {
  key: string;
  value: string;
}
