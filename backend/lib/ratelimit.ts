/**
 * Best-effort in-memory rate limiter.
 *
 * KNOWN LIMITATION, documented rather than hidden: serverless instances do not
 * share memory, so the effective limit is per instance. This is a cheap abuse
 * brake for a single-user personal assistant, not a security control. If NUVA
 * ever becomes multi-tenant, move this to Upstash/Postgres (see
 * docs/roadmap.md).
 */
const WINDOW_MS = 60_000;
const MAX_TRACKED_KEYS = 5_000;

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
