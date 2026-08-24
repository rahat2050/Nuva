/**
 * Rate limiter tests: the original in-memory limiter, the Upstash REST
 * distributed limiter (against a stubbed fetch), and the never-throw fallback
 * contract when Upstash is unreachable.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { checkRateLimit, checkRateLimitDistributed, resetRateLimits } from '../lib/ratelimit';
import { createLogger } from '../lib/logger';
import type { NuvaEnv } from '../lib/env';

const logger = createLogger({ endpoint: 'ratelimit-test' });

function envWithUpstash(): NuvaEnv {
  return {
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
    logLevel: 'error',
    isProduction: false,
    upstash: { url: 'https://example-upstash.upstash.io', token: 'test-token' },
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  resetRateLimits();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('in-memory checkRateLimit', () => {
  it('allows up to the limit then blocks with a retry hint', () => {
    expect(checkRateLimit('k', 2).allowed).toBe(true);
    expect(checkRateLimit('k', 2).allowed).toBe(true);
    const blocked = checkRateLimit('k', 2);
    expect(blocked.allowed).toBe(false);
    expect(blocked.retryAfterSeconds).toBeGreaterThanOrEqual(1);
  });

  it('tracks keys independently', () => {
    checkRateLimit('a', 1);
    expect(checkRateLimit('a', 1).allowed).toBe(false);
    expect(checkRateLimit('b', 1).allowed).toBe(true);
  });
});

describe('checkRateLimitDistributed (Upstash)', () => {
  it('uses the Upstash pipeline counter when configured', async () => {
    const counters = new Map<string, number>();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_input: unknown, init?: { body?: string }) => {
        const body = JSON.parse(String(init?.body ?? '[]')) as Array<{ 0?: string } & unknown[]>;
        const key = (body[0] as unknown as { 0?: string })[0] ?? 'unknown';
        const next = (counters.get(key) ?? 0) + 1;
        counters.set(key, next);
        return jsonResponse([{ result: next }, { result: true }]);
      }),
    );

    const env = envWithUpstash();
    const first = await checkRateLimitDistributed('tenant', 2, env, logger);
    expect(first.mode).toBe('upstash');
    expect(first.allowed).toBe(true);
    expect(first.remaining).toBe(1);

    const second = await checkRateLimitDistributed('tenant', 2, env, logger);
    expect(second.allowed).toBe(true);
    expect(second.remaining).toBe(0);

    const third = await checkRateLimitDistributed('tenant', 2, env, logger);
    expect(third.allowed).toBe(false);
    expect(third.retryAfterSeconds).toBeGreaterThanOrEqual(1);
  });

  it('falls back to the in-memory limiter when Upstash fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ error: 'boom' }, 500)),
    );

    const env = envWithUpstash();
    const decision = await checkRateLimitDistributed('fallback', 60, env, logger);
    expect(decision.mode).toBe('memory');
    expect(decision.allowed).toBe(true);
  });

  it('falls back to the in-memory limiter when Upstash is unreachable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new Error('network down');
      }),
    );

    const env = envWithUpstash();
    const decision = await checkRateLimitDistributed('fallback', 60, env, logger);
    expect(decision.mode).toBe('memory');
    expect(decision.allowed).toBe(true);
  });

  it('reports malformed Upstash results as memory mode', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([{ result: 'not-a-number' }])));
    const env = envWithUpstash();
    const decision = await checkRateLimitDistributed('weird', 60, env, logger);
    expect(decision.mode).toBe('memory');
  });

  it('uses memory mode when Upstash is not configured', async () => {
    const env = envWithUpstash();
    const plain: NuvaEnv = { ...env, upstash: null };
    const decision = await checkRateLimitDistributed('plain', 60, plain, logger);
    expect(decision.mode).toBe('memory');
  });
});
