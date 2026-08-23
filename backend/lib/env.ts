/**
 * Environment configuration.
 *
 * Read lazily on every call: serverless instances are reused across requests but
 * `process.env` is the only supported secret channel (§12). No secret value is
 * ever returned to a client — only booleans describing whether it is present.
 */

const DEFAULTS = {
  /**
   * Groq production models verified against console.groq.com on 2026-08-23.
   * llama-3.3-70b-versatile / llama-3.1-8b-instant were shut down 2026-08-16,
   * so they must not be used as defaults.
   */
  GROQ_MODEL: 'openai/gpt-oss-20b',
  GROQ_FALLBACK_MODEL: 'openai/gpt-oss-120b',
  GROQ_BASE_URL: 'https://api.groq.com/openai/v1',
  GROQ_TIMEOUT_MS: 12_000,
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

function str(name: string): string | null {
  const raw = process.env[name];
  if (raw === undefined) return null;
  const trimmed = raw.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function bool(name: string, fallback: boolean): boolean {
  const raw = str(name);
  if (raw === null) return fallback;
  return ['1', 'true', 'yes', 'on'].includes(raw.toLowerCase());
}

function int(name: string, fallback: number, min: number, max: number): number {
  const raw = str(name);
  if (raw === null) return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function logLevel(): LogLevel {
  const raw = (str('NUVA_LOG_LEVEL') ?? DEFAULTS.LOG_LEVEL).toLowerCase();
  return raw === 'debug' || raw === 'info' || raw === 'warn' || raw === 'error' ? raw : 'info';
}

export function getEnv(): NuvaEnv {
  const serviceRoleKey = str('SUPABASE_SERVICE_ROLE_KEY');
  const reasoning = process.env['GROQ_REASONING_EFFORT'];
  // An explicitly empty value disables the parameter; an absent value uses the default.
  const effort = reasoning === undefined ? DEFAULTS.GROQ_REASONING_EFFORT : reasoning.trim();

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
}

export function groqConfigured(env: NuvaEnv = getEnv()): boolean {
  return env.groqApiKey !== null;
}

/** Anon key + URL are the minimum needed to verify user JWTs. */
export function supabaseConfigured(env: NuvaEnv = getEnv()): boolean {
  return env.supabaseUrl !== null && env.supabaseAnonKey !== null;
}

/** Service role is required for server-side writes that bypass RLS. */
export function supabaseWritable(env: NuvaEnv = getEnv()): boolean {
  return env.supabaseUrl !== null && env.supabaseServiceRoleKey !== null;
}

/** Client-safe summary. Contains no secret material by construction. */
export function envSummary(env: NuvaEnv = getEnv()) {
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
}
