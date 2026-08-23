/**
 * Environment configuration.
 *
 * Read lazily on every call: serverless instances are reused across requests but
 * `process.env` is the only supported secret channel (§12). No secret value is
 * ever returned to a client — only booleans describing whether it is present.
 *
 * HARDENED: All access to process.env is wrapped in try/catch so that if
 * process is undefined (edge runtime) or env var access throws, we return
 * safe defaults instead of crashing with FUNCTION_INVOCATION_FAILED.
 */

const DEFAULTS = {
  /**
   * Groq production models verified against console.groq.com on 2026-08-23.
   * llama-3.3-70b-versatile / llama-3.1-8b-instant were shut down 2026-08-16,
   * so they must not be used as defaults.
   *
   * TIMEOUT: 8s to fit inside Vercel's 10s Hobby maxDuration with room for
   * validation/risk checks. Previously 12s could cause the function to be
   * killed by Vercel before our AbortController fires.
   */
  GROQ_MODEL: 'openai/gpt-oss-20b',
  GROQ_FALLBACK_MODEL: 'openai/gpt-oss-120b',
  GROQ_BASE_URL: 'https://api.groq.com/openai/v1',
  GROQ_TIMEOUT_MS: 8_000,
  GROQ_REASONING_EFFORT: 'low',
  RATE_LIMIT_PER_MIN: 60,
  LOG_LEVEL: 'info',
} as const;

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface NuvaEnv {
  groqApiKey: string | null;
  groqModel: string;
  groqFallbackModel: string;
  groqBaseUrl: string;
  groqTimeoutMs: number;
  groqReasoningEffort: string | null;

  supabaseUrl: string | null;
  supabaseAnonKey: string | null;
  supabaseServiceRoleKey: string | null;

  requireAuth: boolean;
  persistEnabled: boolean;
  allowFallbackParser: boolean;
  allowedOrigins: string[];
  rateLimitPerMin: number;
  logLevel: LogLevel;
  isProduction: boolean;
}

function safeEnv(): Record<string, string | undefined> {
  try {
    // In some edge runtimes, process is undefined. Return empty object.
    if (typeof process === 'undefined' || !process.env) return {};
    return process.env as Record<string, string | undefined>;
  } catch {
    return {};
  }
}

function str(name: string): string | null {
  try {
    const env = safeEnv();
    const raw = env[name];
    if (raw === undefined) return null;
    const trimmed = raw.trim();
    return trimmed.length === 0 ? null : trimmed;
  } catch {
    return null;
  }
}

function bool(name: string, fallback: boolean): boolean {
  try {
    const raw = str(name);
    if (raw === null) return fallback;
    return ['1', 'true', 'yes', 'on'].includes(raw.toLowerCase());
  } catch {
    return fallback;
  }
}

function int(name: string, fallback: number, min: number, max: number): number {
  try {
    const raw = str(name);
    if (raw === null) return fallback;
    const parsed = Number.parseInt(raw, 10);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.min(max, Math.max(min, parsed));
  } catch {
    return fallback;
  }
}

function logLevel(): LogLevel {
  try {
    const raw = (str('NUVA_LOG_LEVEL') ?? DEFAULTS.LOG_LEVEL).toLowerCase();
    return raw === 'debug' || raw === 'info' || raw === 'warn' || raw === 'error' ? raw : 'info';
  } catch {
    return 'info';
  }
}

export function getEnv(): NuvaEnv {
  try {
    const env = safeEnv();
    const serviceRoleKey = str('SUPABASE_SERVICE_ROLE_KEY');
    const reasoningRaw = env['GROQ_REASONING_EFFORT'];
    // An explicitly empty value disables the parameter; an absent value uses the default.
    const effort = reasoningRaw === undefined ? DEFAULTS.GROQ_REASONING_EFFORT : (reasoningRaw ?? '').trim();

    return {
      groqApiKey: str('GROQ_API_KEY'),
      groqModel: str('GROQ_MODEL') ?? DEFAULTS.GROQ_MODEL,
      groqFallbackModel: str('GROQ_FALLBACK_MODEL') ?? DEFAULTS.GROQ_FALLBACK_MODEL,
      groqBaseUrl: (str('GROQ_BASE_URL') ?? DEFAULTS.GROQ_BASE_URL).replace(/\/+$/, ''),
      groqTimeoutMs: int('GROQ_TIMEOUT_MS', DEFAULTS.GROQ_TIMEOUT_MS, 1_000, 60_000),
      groqReasoningEffort: effort.length > 0 ? effort : null,

      supabaseUrl: str('SUPABASE_URL'),
      supabaseAnonKey: str('SUPABASE_ANON_KEY'),
      supabaseServiceRoleKey: serviceRoleKey,

      requireAuth: bool('NUVA_REQUIRE_AUTH', false),
      persistEnabled: bool('NUVA_PERSIST', true) && serviceRoleKey !== null,
      allowFallbackParser: bool('NUVA_ALLOW_FALLBACK_PARSER', true),
      allowedOrigins: (str('NUVA_ALLOWED_ORIGINS') ?? '*')
        .split(',')
        .map((o) => o.trim())
        .filter((o) => o.length > 0),
      rateLimitPerMin: int('NUVA_RATE_LIMIT_PER_MIN', DEFAULTS.RATE_LIMIT_PER_MIN, 1, 10_000),
      logLevel: logLevel(),
      isProduction: (str('VERCEL_ENV') ?? str('NODE_ENV')) === 'production',
    };
  } catch {
    // Ultimate fallback: return safe defaults that allow the health check to
    // still respond with 200 and explain that env is misconfigured.
    return {
      groqApiKey: null,
      groqModel: DEFAULTS.GROQ_MODEL,
      groqFallbackModel: DEFAULTS.GROQ_FALLBACK_MODEL,
      groqBaseUrl: DEFAULTS.GROQ_BASE_URL,
      groqTimeoutMs: DEFAULTS.GROQ_TIMEOUT_MS,
      groqReasoningEffort: DEFAULTS.GROQ_REASONING_EFFORT,
      supabaseUrl: null,
      supabaseAnonKey: null,
      supabaseServiceRoleKey: null,
      requireAuth: false,
      persistEnabled: false,
      allowFallbackParser: true,
      allowedOrigins: ['*'],
      rateLimitPerMin: DEFAULTS.RATE_LIMIT_PER_MIN,
      logLevel: 'info',
      isProduction: false,
    };
  }
}

export function groqConfigured(env: NuvaEnv = getEnv()): boolean {
  try {
    return env.groqApiKey !== null;
  } catch {
    return false;
  }
}

/** Anon key + URL are the minimum needed to verify user JWTs. */
export function supabaseConfigured(env: NuvaEnv = getEnv()): boolean {
  try {
    return env.supabaseUrl !== null && env.supabaseAnonKey !== null;
  } catch {
    return false;
  }
}

/** Service role is required for server-side writes that bypass RLS. */
export function supabaseWritable(env: NuvaEnv = getEnv()): boolean {
  try {
    return env.supabaseUrl !== null && env.supabaseServiceRoleKey !== null;
  } catch {
    return false;
  }
}

/** Client-safe summary. Contains no secret material by construction. */
export function envSummary(env: NuvaEnv = getEnv()) {
  try {
    return {
      groq: {
        configured: groqConfigured(env),
        model: env.groqModel,
        fallback_model: env.groqFallbackModel,
      },
      supabase: {
        configured: supabaseConfigured(env),
        service_role: env.supabaseServiceRoleKey !== null,
      },
      auth_required: env.requireAuth,
      persistence: env.persistEnabled,
      fallback_parser: env.allowFallbackParser,
    };
  } catch {
    return {
      groq: { configured: false, model: DEFAULTS.GROQ_MODEL, fallback_model: DEFAULTS.GROQ_FALLBACK_MODEL },
      supabase: { configured: false, service_role: false },
      auth_required: false,
      persistence: false,
      fallback_parser: true,
    };
  }
}
