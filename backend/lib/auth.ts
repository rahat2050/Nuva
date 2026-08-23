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
 */
import { getAnonClient } from './supabase';
import { getEnv, supabaseConfigured, type NuvaEnv } from './env';
import { NuvaError } from './errors';
import type { Logger } from './logger';
import type { VercelRequest } from '@vercel/node';

export interface Identity {
  userId: string | null;
  deviceId: string | null;
  /** Best-effort client IP, used only for rate limiting. */
  ip: string | null;
}

const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{4,128}$/;

export function headerValue(req: VercelRequest, name: string): string | null {
  const raw = req.headers[name.toLowerCase()];
  if (Array.isArray(raw)) return raw[0] ?? null;
  return typeof raw === 'string' && raw.length > 0 ? raw : null;
}

export function clientIp(req: VercelRequest): string | null {
  const forwarded = headerValue(req, 'x-forwarded-for');
  if (forwarded) {
    const first = forwarded.split(',')[0]?.trim();
    if (first) return first;
  }
  return headerValue(req, 'x-real-ip') ?? req.socket?.remoteAddress ?? null;
}

function bearerToken(req: VercelRequest): string | null {
  const header = headerValue(req, 'authorization');
  if (!header) return null;
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  const token = match?.[1]?.trim();
  return token && token.length > 0 ? token : null;
}

function deviceId(req: VercelRequest, bodyDeviceId?: unknown): string | null {
  const candidate = headerValue(req, 'x-nuva-device-id') ?? (typeof bodyDeviceId === 'string' ? bodyDeviceId : null);
  if (!candidate) return null;
  return DEVICE_ID_PATTERN.test(candidate) ? candidate : null;
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
  const env = options.env ?? getEnv();
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
    const { data, error } = await getAnonClient(env).auth.getUser(token);
    if (error || !data.user) {
      logger.warn('rejected bearer token', { reason: error?.message ?? 'no user' });
      throw new NuvaError('UNAUTHORIZED', 'The access token is invalid or expired', { expected: true });
    }
    identity.userId = data.user.id;
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
  return identity.userId ?? identity.deviceId ?? identity.ip ?? 'anonymous';
}
