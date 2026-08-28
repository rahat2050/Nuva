/**
 * Request identity.
 *
 * NUVA deliberately has NO shared app secret. A pre-shared key shipped inside
 * the APK would be a server-side secret embedded in the client, which §12
 * forbids. Instead the Android app authenticates the *user* with Supabase and
 * forwards that user's JWT, which the backend verifies on every request.
 *
 * When SUPABASE is unconfigured or no token is sent, the request is anonymous:
 * command interpretation still works, but nothing is persisted and per-user
 * endpoints return 401.
 *
 * HARDENED: All header access is wrapped in try/catch to avoid crashes when
 * req.headers is undefined or malformed (which would otherwise become
 * FUNCTION_INVOCATION_FAILED on Vercel).
 */
import { getEnv, supabaseConfigured, type NuvaEnv } from './env.js';
import { NuvaError } from './errors.js';
import type { Logger } from './logger.js';
import type { VercelRequest } from '../types/vercel.js';

export interface Identity {
  userId: string | null;
  deviceId: string | null;
  /** Best-effort client IP, used only for rate limiting. */
  ip: string | null;
}

const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{4,128}$/;

export function headerValue(req: VercelRequest, name: string): string | null {
  try {
    const headers = (req as unknown as { headers?: Record<string, unknown> }).headers;
    if (!headers) return null;
    const raw = headers[name.toLowerCase()];
    if (Array.isArray(raw)) return (raw[0] as string) ?? null;
    return typeof raw === 'string' && raw.length > 0 ? raw : null;
  } catch {
    return null;
  }
}

export function clientIp(req: VercelRequest): string | null {
  try {
    const forwarded = headerValue(req, 'x-forwarded-for');
    if (forwarded) {
      const first = forwarded.split(',')[0]?.trim();
      if (first) return first;
    }
    const realIp = headerValue(req, 'x-real-ip');
    if (realIp) return realIp;
    const socket = (req as unknown as { socket?: { remoteAddress?: string } }).socket;
    return socket?.remoteAddress ?? null;
  } catch {
    return null;
  }
}

function bearerToken(req: VercelRequest): string | null {
  try {
    const header = headerValue(req, 'authorization');
    if (!header) return null;
    const match = /^Bearer\s+(.+)$/i.exec(header.trim());
    const token = match?.[1]?.trim();
    return token && token.length > 0 ? token : null;
  } catch {
    return null;
  }
}

function deviceId(req: VercelRequest, bodyDeviceId?: unknown): string | null {
  try {
    const candidate = headerValue(req, 'x-nuva-device-id') ?? (typeof bodyDeviceId === 'string' ? bodyDeviceId : null);
    if (!candidate) return null;
    return DEVICE_ID_PATTERN.test(candidate) ? candidate : null;
  } catch {
    return null;
  }
}

/**
 * Verifies the bearer token against Supabase, if one was supplied.
 * An invalid token is a hard 401 — we never silently downgrade to anonymous,
 * because the caller would then think their data was being saved.
 */
export async function resolveIdentity(
  req: VercelRequest,
  logger: Logger,
  options: { bodyDeviceId?: unknown; env?: NuvaEnv } = {},
): Promise<Identity> {
  let env: NuvaEnv;
  try {
    env = options.env ?? getEnv();
  } catch {
    // If env parsing fails, use safe defaults that allow anonymous access
    env = {
      groqApiKey: null,
      groqModel: 'openai/gpt-oss-20b',
      groqFallbackModel: 'openai/gpt-oss-120b',
      groqBaseUrl: 'https://api.groq.com/openai/v1',
      groqTimeoutMs: 8000,
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

  const identity: Identity = {
    userId: null,
    deviceId: deviceId(req, options.bodyDeviceId),
    ip: clientIp(req),
  };

  const token = bearerToken(req);

  if (token !== null) {
    if (!supabaseConfigured(env)) {
      throw new NuvaError('NOT_CONFIGURED', 'A bearer token was sent but Supabase auth is not configured');
    }
    // Only load the Supabase SDK when authentication is actually requested.
    // This keeps anonymous routes (especially /api/health) bootable even when
    // Supabase is intentionally not configured or its optional SDK bundle is
    // unavailable in a deployment.
    try {
      const { getAnonClient } = await import('./supabase.js');
      const { data, error } = await getAnonClient(env).auth.getUser(token);
      if (error || !data.user) {
        try {
          logger.warn('rejected bearer token', { reason: error?.message ?? 'no user' });
        } catch {
          // ignore logger failure
        }
        throw new NuvaError('UNAUTHORIZED', 'The access token is invalid or expired', { expected: true });
      }
      identity.userId = data.user.id;
    } catch (err) {
      if (err instanceof NuvaError) throw err;
      throw new NuvaError('NOT_CONFIGURED', `Auth check failed: ${err instanceof Error ? err.message : 'unknown'}`, {
        cause: err,
      });
    }
  }

  if (env.requireAuth && identity.userId === null) {
    throw new NuvaError('UNAUTHORIZED', 'Authentication is required (NUVA_REQUIRE_AUTH=true)', { expected: true });
  }

  return identity;
}

/** For endpoints that are inherently per-user (history, memory). */
export function requireUser(identity: Identity): string {
  if (identity.userId === null) {
    throw new NuvaError('UNAUTHORIZED', 'Sign in with Supabase to use this endpoint', { expected: true });
  }
  return identity.userId;
}

/** Stable-ish rate limit bucket key. */
export function rateLimitKey(identity: Identity): string {
  try {
    return identity.userId ?? identity.deviceId ?? identity.ip ?? 'anonymous';
  } catch {
    return 'anonymous';
  }
}
