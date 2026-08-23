/**
 * Shared HTTP plumbing for every NUVA endpoint: request ids, CORS, security
 * headers, method guards, body limits, rate limiting, timing and a single
 * error funnel so nothing ever fails silently (§24).
 *
 * HARDENED FOR VERCEL:
 * - All env/logger creation is inside the try/catch so a mis-configured env
 *   never becomes FUNCTION_INVOCATION_FAILED.
 * - randomUUID has multiple fallbacks (node:crypto → globalThis.crypto → Math.random)
 * - Security headers and CORS are applied defensively.
 */
import { getEnv, type NuvaEnv } from './env';
import { NuvaError, toNuvaError } from './errors';
import { createLogger, type Logger } from './logger';
import { checkRateLimit } from './ratelimit';
import { clientIp, headerValue } from './auth';
import { detectLanguage } from './normalize';
import { LANGUAGES, type Language } from '../types/action';
import type { ApiErrorBody } from '../types/api';
import type { VercelRequest, VercelResponse } from '@vercel/node';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

const MAX_BODY_BYTES = 32 * 1024;

export interface ApiContext {
  req: VercelRequest;
  res: VercelResponse;
  requestId: string;
  logger: Logger;
  env: NuvaEnv;
  method: HttpMethod;
  body: Record<string, unknown>;
  query: Record<string, string>;
  /** Best-effort language for user-facing error speech. */
  language: Language;
}

export interface ApiResult {
  status: number;
  body: unknown;
}

function safeRandomUUID(): string {
  try {
    // Node 14.17+ provides randomUUID in node:crypto, but dynamic import
    // avoids a hard top-level import that could crash in edge-like envs.
    // We try globalThis.crypto first (available in Node 19+ and browsers),
    // then node:crypto, then a Math.random fallback.
    const g = globalThis as unknown as { crypto?: { randomUUID?: () => string } };
    if (g.crypto?.randomUUID) return g.crypto.randomUUID();
  } catch {
    // ignore
  }
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { randomUUID } = require('node:crypto') as { randomUUID: () => string };
    if (typeof randomUUID === 'function') return randomUUID();
  } catch {
    // ignore
  }
  // Last resort: not cryptographically strong, but better than crashing.
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${Math.random().toString(36).slice(2, 10)}`;
}

function applySecurityHeaders(res: VercelResponse): void {
  try {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Cache-Control', 'no-store, max-age=0');
    res.setHeader('Permissions-Policy', 'geolocation=(), microphone=(), camera=()');
  } catch {
    // If headers already sent or res is mocked, ignore.
  }
}

function applyCors(req: VercelRequest, res: VercelResponse, env: NuvaEnv, methods: HttpMethod[]): void {
  try {
    const origin = headerValue(req, 'origin');
    if (env.allowedOrigins.includes('*')) {
      res.setHeader('Access-Control-Allow-Origin', '*');
    } else if (origin && env.allowedOrigins.includes(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
    }
    res.setHeader('Access-Control-Allow-Methods', [...methods, 'OPTIONS'].join(', '));
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Nuva-Device-Id');
    res.setHeader('Access-Control-Max-Age', '600');
  } catch {
    // Non-fatal
  }
}

/** Vercel pre-parses JSON bodies; the local dev harness may hand us a string. */
function parseBody(req: VercelRequest): Record<string, unknown> {
  const contentLength = Number.parseInt(headerValue(req, 'content-length') ?? '0', 10);
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    throw new NuvaError('PAYLOAD_TOO_LARGE', `Request body exceeds ${MAX_BODY_BYTES} bytes`);
  }

  const raw: unknown = req.body;
  if (raw === undefined || raw === null || raw === '') return {};

  if (typeof raw === 'string' || Buffer.isBuffer(raw)) {
    const text = raw.toString();
    if (text.length > MAX_BODY_BYTES) {
      throw new NuvaError('PAYLOAD_TOO_LARGE', `Request body exceeds ${MAX_BODY_BYTES} bytes`);
    }
    if (text.trim().length === 0) return {};
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      throw new NuvaError('BAD_REQUEST', 'Request body is not valid JSON');
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new NuvaError('BAD_REQUEST', 'Request body must be a JSON object');
    }
    return parsed as Record<string, unknown>;
  }

  if (typeof raw === 'object' && !Array.isArray(raw)) return raw as Record<string, unknown>;
  throw new NuvaError('BAD_REQUEST', 'Request body must be a JSON object');
}

function flattenQuery(req: VercelRequest): Record<string, string> {
  const out: Record<string, string> = {};
  try {
    for (const [key, value] of Object.entries(req.query ?? {})) {
      if (typeof value === 'string') out[key] = value;
      else if (Array.isArray(value) && typeof value[0] === 'string') out[key] = value[0];
    }
  } catch {
    // ignore malformed query
  }
  return out;
}

/** Language hint for error speech: explicit field first, then transcript. */
function resolveLanguage(body: Record<string, unknown>): Language {
  const hint = body['language'];
  if (typeof hint === 'string' && (LANGUAGES as readonly string[]).includes(hint)) return hint as Language;
  const text = body['text'];
  return typeof text === 'string' && text.length > 0 ? detectLanguage(text) : 'en';
}

export interface HandlerOptions {
  name: string;
  methods: HttpMethod[];
  /** Apply the per-identity request cap. Off for /api/health. */
  rateLimit?: boolean;
  handler: (ctx: ApiContext) => Promise<ApiResult>;
}

export function defineHandler(options: HandlerOptions) {
  return async function nuvaHandler(req: VercelRequest, res: VercelResponse): Promise<void> {
    // These are initialized with safe fallbacks BEFORE the try, so the catch
    // block can always report a request_id even if getEnv() itself throws.
    let env: NuvaEnv;
    let requestId: string;
    let logger: Logger;
    let startedAt = Date.now();
    let language: Language = 'en';

    try {
      env = getEnv();
    } catch {
      // If env parsing itself crashes, use minimal safe defaults so we can
      // still return a structured JSON error instead of FUNCTION_INVOCATION_FAILED.
      env = {
        groqApiKey: null,
        groqModel: 'openai/gpt-oss-20b',
        groqFallbackModel: 'openai/gpt-oss-120b',
        groqBaseUrl: 'https://api.groq.com/openai/v1',
        groqTimeoutMs: 12000,
        groqReasoningEffort: 'low',
        supabaseUrl: null,
        supabaseAnonKey: null,
        supabaseServiceRoleKey: null,
        requireAuth: false,
        persistEnabled: false,
        allowFallbackParser: true,
        allowedOrigins: ['*'],
        rateLimitPerMin: 60,
        logLevel: 'info',
        isProduction: false,
      };
    }

    try {
      requestId = headerValue(req, 'x-vercel-id') ?? safeRandomUUID();
    } catch {
      requestId = safeRandomUUID();
    }

    try {
      startedAt = Date.now();
      logger = createLogger({ request_id: requestId, endpoint: options.name });
    } catch {
      // Fallback logger that never throws
      logger = {
        debug: () => {},
        info: () => {},
        warn: () => {},
        error: () => {},
        child: () => logger,
      } as unknown as Logger;
    }

    // Always apply security headers, even if env was fallback.
    applySecurityHeaders(res);
    try {
      applyCors(req, res, env, options.methods);
      res.setHeader('X-Request-Id', requestId);
    } catch {
      // ignore
    }

    if (req.method === 'OPTIONS') {
      try {
        res.status(204).end();
      } catch {
        // ignore
      }
      return;
    }

    try {
      const method = (req.method ?? 'GET').toUpperCase() as HttpMethod;
      if (!options.methods.includes(method)) {
        res.setHeader('Allow', [...options.methods, 'OPTIONS'].join(', '));
        throw new NuvaError('METHOD_NOT_ALLOWED', `${method} is not supported here; use ${options.methods.join(', ')}`, {
          expected: true,
        });
      }

      const body = method === 'GET' || method === 'DELETE' ? {} : parseBody(req);
      language = resolveLanguage(body);

      if (options.rateLimit) {
        const key = `${options.name}:${headerValue(req, 'x-nuva-device-id') ?? clientIp(req) ?? 'anonymous'}`;
        const decision = checkRateLimit(key, env.rateLimitPerMin);
        try {
          res.setHeader('X-RateLimit-Limit', String(decision.limit));
          res.setHeader('X-RateLimit-Remaining', String(decision.remaining));
          if (!decision.allowed) {
            res.setHeader('Retry-After', String(decision.retryAfterSeconds));
          }
        } catch {
          // ignore header errors
        }
        if (!decision.allowed) {
          throw new NuvaError('RATE_LIMITED', `Rate limit of ${decision.limit}/min exceeded`, { expected: true });
        }
      }

      const result = await options.handler({
        req,
        res,
        requestId,
        logger,
        env,
        method,
        body,
        query: flattenQuery(req),
        language,
      });

      try {
        logger.info('request completed', { status: result.status, duration_ms: Date.now() - startedAt });
      } catch {
        // ignore logger failure
      }
      res.status(result.status).json(result.body);
    } catch (err) {
      const error = toNuvaError(err);

      const body: ApiErrorBody = {
        ok: false,
        request_id: requestId,
        error: {
          code: error.code,
          message: error.message,
          speech: error.speech(language),
        },
      };
      if (error.details !== undefined) body.error.details = error.details;

      const fields = {
        code: error.code,
        status: error.status,
        duration_ms: Date.now() - startedAt,
        error: error.message,
      };
      try {
        if (error.expected) logger.warn('request rejected', fields);
        else logger.error('request failed', fields);
      } catch {
        // logger failed, ignore
      }

      try {
        res.status(error.status).json(body);
      } catch {
        // If res.json fails (headers already sent), try end()
        try {
          res.status(error.status).end(JSON.stringify(body));
        } catch {
          // Last resort: do nothing, Vercel will return 500 but we tried
        }
      }
    }
  };
}

export function ok(body: unknown): ApiResult {
  return { status: 200, body };
}
