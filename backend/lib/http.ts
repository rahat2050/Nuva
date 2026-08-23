/**
 * Shared HTTP plumbing for every NUVA endpoint: request ids, CORS, security
 * headers, method guards, body limits, rate limiting, timing and a single
 * error funnel so nothing ever fails silently (§24).
 */
import { randomUUID } from 'node:crypto';
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

function applySecurityHeaders(res: VercelResponse): void {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Cache-Control', 'no-store, max-age=0');
  res.setHeader('Permissions-Policy', 'geolocation=(), microphone=(), camera=()');
}

function applyCors(req: VercelRequest, res: VercelResponse, env: NuvaEnv, methods: HttpMethod[]): void {
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
  for (const [key, value] of Object.entries(req.query ?? {})) {
    if (typeof value === 'string') out[key] = value;
    else if (Array.isArray(value) && typeof value[0] === 'string') out[key] = value[0];
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
    const env = getEnv();
    const requestId = headerValue(req, 'x-vercel-id') ?? randomUUID();
    const startedAt = Date.now();
    const logger = createLogger({ request_id: requestId, endpoint: options.name });

    applySecurityHeaders(res);
    applyCors(req, res, env, options.methods);
    res.setHeader('X-Request-Id', requestId);

    if (req.method === 'OPTIONS') {
      res.status(204).end();
      return;
    }

    let language: Language = 'en';

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
        res.setHeader('X-RateLimit-Limit', String(decision.limit));
        res.setHeader('X-RateLimit-Remaining', String(decision.remaining));
        if (!decision.allowed) {
          res.setHeader('Retry-After', String(decision.retryAfterSeconds));
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

      logger.info('request completed', { status: result.status, duration_ms: Date.now() - startedAt });
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
      if (error.expected) logger.warn('request rejected', fields);
      else logger.error('request failed', fields);

      res.status(error.status).json(body);
    }
  };
}

export function ok(body: unknown): ApiResult {
  return { status: 200, body };
}
