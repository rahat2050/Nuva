/**
 * Rate limiting for NUVA.
 *
 * Two modes, chosen automatically from the environment (roadmap follow-up
 * "Distributed rate limiting"):
 *
 *   * memory   — the original best-effort in-memory limiter. Serverless
 *                instances do not share memory, so the effective limit is per
 *                instance. Fine as a single-user abuse brake.
 *   * upstash  — when UPSTASH_REDIS_REST_URL + UPSTASH_REDIS_REST_TOKEN are
 *                set, decisions are made against Upstash Redis over its REST
 *                pipeline endpoint (plain fetch, no SDK), so the limit is
 *                global across all serverless instances.
 *
 * Contract: if Upstash cannot be reached, we degrade to the in-memory limiter
 * and log a warning. Rate limiting must never take the API down.
 */
import { getEnv, upstashConfigured, type NuvaEnv } from './env';
import type { Logger } from './logger';

const WINDOW_MS = 60_000;
const MAX_TRACKED_KEYS = 5_000;
/** Bucket keys in Upstash live one extra minute so expiry cleanup is certain. */
const UPSTASH_TTL_SECONDS = 120;
const UPSTASH_TIMEOUT_MS = 1_500;

interface Bucket {
  count: number;
  resetAt: number;
}

const buckets = new Map<string, Bucket>();

export interface RateLimitDecision {
  allowed: boolean;
  remaining: number;
  limit: number;
  retryAfterSeconds: number;
}

export interface DistributedRateLimitDecision extends RateLimitDecision {
  mode: 'upstash' | 'memory';
}

function sweep(now: number): void {
  if (buckets.size < MAX_TRACKED_KEYS) return;
  for (const [key, bucket] of buckets) {
    if (bucket.resetAt <= now) buckets.delete(key);
  }
  // Still oversized (pathological traffic): drop everything rather than leak.
  if (buckets.size >= MAX_TRACKED_KEYS) buckets.clear();
}

export function checkRateLimit(key: string, limitPerMinute: number, now: number = Date.now()): RateLimitDecision {
  sweep(now);

  const existing = buckets.get(key);
  if (!existing || existing.resetAt <= now) {
    buckets.set(key, { count: 1, resetAt: now + WINDOW_MS });
    return { allowed: true, remaining: limitPerMinute - 1, limit: limitPerMinute, retryAfterSeconds: 0 };
  }

  existing.count += 1;
  const remaining = Math.max(0, limitPerMinute - existing.count);
  const allowed = existing.count <= limitPerMinute;

  return {
    allowed,
    remaining,
    limit: limitPerMinute,
    retryAfterSeconds: allowed ? 0 : Math.max(1, Math.ceil((existing.resetAt - now) / 1000)),
  };
}

/** Test-only helper. */
export function resetRateLimits(): void {
  buckets.clear();
}

function getFetch(): typeof fetch | null {
  try {
    if (typeof globalThis.fetch === 'function') return globalThis.fetch.bind(globalThis);
    return null;
  } catch {
    return null;
  }
}

/**
 * Fixed-window counter in Upstash Redis, using the REST pipeline endpoint:
 * one round-trip that increments a per-minute bucket and (NX) sets its TTL.
 * The key embeds the minute number, so every minute starts a fresh bucket.
 */
async function upstashFixedWindow(
  key: string,
  limitPerMinute: number,
  config: { url: string; token: string },
): Promise<RateLimitDecision> {
  const fetchFn = getFetch();
  if (!fetchFn) throw new Error('fetch is not available in this runtime');

  const nowMs = Date.now();
  const minute = Math.floor(nowMs / WINDOW_MS);
  const bucketKey = `nuva:rl:${key}:${minute}`;

  let controller: AbortController | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  try {
    controller = new AbortController();
    timer = setTimeout(() => {
      try {
        controller?.abort();
      } catch {
        // ignore
      }
    }, UPSTASH_TIMEOUT_MS);
  } catch {
    // no timeout support
  }

  try {
    const response = await fetchFn(`${config.url}/pipeline`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify([
        ['INCR', bucketKey],
        ['EXPIRE', bucketKey, String(UPSTASH_TTL_SECONDS), 'NX'],
      ]),
      ...(controller ? { signal: controller.signal } : {}),
    });

    if (!response.ok) throw new Error(`Upstash responded with ${response.status}`);

    const payload = (await response.json()) as Array<{ result?: unknown; error?: unknown }>;
    const count = payload?.[0]?.result;
    if (typeof count !== 'number') throw new Error('Unexpected Upstash pipeline result');

    const allowed = count <= limitPerMinute;
    const resetAt = (minute + 1) * WINDOW_MS;
    return {
      allowed,
      remaining: Math.max(0, limitPerMinute - count),
      limit: limitPerMinute,
      retryAfterSeconds: allowed ? 0 : Math.max(1, Math.ceil((resetAt - nowMs) / 1000)),
    };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * The rate limiter every endpoint uses. Prefers the distributed Upstash
 * counter; falls back to the in-memory limiter whenever Upstash is not
 * configured or unreachable. Never throws.
 */
export async function checkRateLimitDistributed(
  key: string,
  limitPerMinute: number,
  env: NuvaEnv = getEnv(),
  logger?: Logger,
): Promise<DistributedRateLimitDecision> {
  if (upstashConfigured(env) && env.upstash) {
    try {
      const decision = await upstashFixedWindow(key, limitPerMinute, env.upstash);
      return { ...decision, mode: 'upstash' };
    } catch (err) {
      try {
        logger?.warn('upstash rate limit failed, falling back to in-memory limiter', {
          error: err instanceof Error ? err.message : 'unknown',
        });
      } catch {
        // logger failure is non-fatal
      }
    }
  }
  return { ...checkRateLimit(key, limitPerMinute), mode: 'memory' };
}
