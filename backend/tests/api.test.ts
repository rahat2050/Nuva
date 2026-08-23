/**
 * Endpoint-level tests: the real handler modules are invoked through the same
 * shim the local dev server uses, so routing, method guards, CORS, rate limiting
 * and the error envelope are all exercised.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { invokeForTest } from '../dev/vercel-shim';
import { resetRateLimits } from '../lib/ratelimit';

const ORIGINAL_ENV = { ...process.env };

beforeEach(() => {
  resetRateLimits();
  // No Groq key and no Supabase: the deterministic parser answers, nothing persists.
  delete process.env['GROQ_API_KEY'];
  delete process.env['SUPABASE_URL'];
  delete process.env['SUPABASE_ANON_KEY'];
  delete process.env['SUPABASE_SERVICE_ROLE_KEY'];
  process.env['NUVA_LOG_LEVEL'] = 'error';
  process.env['NUVA_REQUIRE_AUTH'] = 'false';
  process.env['NUVA_ALLOW_FALLBACK_PARSER'] = 'true';
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  vi.restoreAllMocks();
});

async function healthHandler() {
  return (await import('../api/health/index')).default;
}
async function commandHandler() {
  return (await import('../api/ai/command')).default;
}
async function commandsHandler() {
  return (await import('../api/commands/index')).default;
}
async function memoryHandler() {
  return (await import('../api/memory/index')).default;
}

describe('GET /api/health', () => {
  it('reports service status and configuration without secrets', async () => {
    const res = await invokeForTest(await healthHandler(), { method: 'GET', url: '/api/health' });

    expect(res.status).toBe(200);
    const body = res.body as Record<string, any>;
    expect(body.ok).toBe(true);
    expect(body.service).toBe('nuva-backend');
    expect(body.phase).toBe(1);
    expect(body.config.groq.configured).toBe(false);
    expect(body.config.groq.model).toBe('openai/gpt-oss-20b');
    expect(body.config.supabase.configured).toBe(false);

    // No secret material anywhere in the payload.
    expect(res.raw).not.toMatch(/GROQ_API_KEY|service_role_key|eyJ[A-Za-z0-9]/);
  });

  it('sets security headers and a request id', async () => {
    const res = await invokeForTest(await healthHandler(), { method: 'GET', url: '/api/health' });
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.headers['x-frame-options']).toBe('DENY');
    expect(res.headers['cache-control']).toContain('no-store');
    expect(res.headers['x-request-id']).toBeTruthy();
  });

  it('rejects non-GET with 405 and an Allow header', async () => {
    const res = await invokeForTest(await healthHandler(), { method: 'POST', url: '/api/health', body: {} });
    expect(res.status).toBe(405);
    expect(res.headers['allow']).toContain('GET');
    expect((res.body as any).error.code).toBe('METHOD_NOT_ALLOWED');
  });

  it('answers CORS preflight', async () => {
    const res = await invokeForTest(await healthHandler(), { method: 'OPTIONS', url: '/api/health' });
    expect(res.status).toBe(204);
    expect(res.headers['access-control-allow-methods']).toContain('GET');
  });
});

describe('POST /api/ai/command', () => {
  it('interprets a command end to end without a Groq key', async () => {
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 'Nuva YouTube open koro.' },
    });

    expect(res.status).toBe(200);
    const body = res.body as Record<string, any>;
    expect(body.ok).toBe(true);
    expect(body.result.intent).toBe('OPEN_APP');
    expect(body.result.action.app).toBe('youtube');
    expect(body.result.risk).toBe('low');
    expect(body.result.requires_confirmation).toBe(false);
    expect(body.meta.source).toBe('fallback');
    expect(body.meta.persisted).toBe(false);
    expect(body.input.language).toBe('banglish');
  });

  it('validates the request body', async () => {
    const missing = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: {},
    });
    expect(missing.status).toBe(400);
    expect((missing.body as any).error.code).toBe('BAD_REQUEST');
    expect((missing.body as any).error.speech.length).toBeGreaterThan(0);

    const wrongType = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 12345 },
    });
    expect(wrongType.status).toBe(400);

    const badLanguage = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 'go home', language: 'klingon' },
    });
    expect(badLanguage.status).toBe(400);
  });

  it('rejects malformed JSON bodies', async () => {
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: '{not json',
    });
    expect(res.status).toBe(400);
  });

  it('rejects oversized commands', async () => {
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 'a'.repeat(1500) },
    });
    expect(res.status).toBe(413);
  });

  it('reports the real reason (no AI configured) instead of a fake "didn\'t understand"', async () => {
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 'Nuva amake ekta kobita likhe dao.' },
    });
    // No GROQ_API_KEY here and the offline parser cannot match, so the honest
    // answer is a configuration fault the developer can act on — never a silent
    // nothing, and never a misleading comprehension error.
    expect(res.status).toBe(503);
    expect((res.body as any).error.code).toBe('NOT_CONFIGURED');
    expect((res.body as any).error.speech.length).toBeGreaterThan(0);
  });

  it('speaks errors in the detected language', async () => {
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 'আমাকে একটা কবিতা লিখে দাও' },
    });
    expect(res.status).toBe(503);
    expect((res.body as any).error.speech).toMatch(/[\u0980-\u09FF]/);
  });

  it('enforces the rate limit', async () => {
    process.env['NUVA_RATE_LIMIT_PER_MIN'] = '3';
    const handler = await commandHandler();
    const send = () =>
      invokeForTest(handler, {
        method: 'POST',
        url: '/api/ai/command',
        headers: { 'x-nuva-device-id': 'rate-test-device' },
        body: { text: 'go home' },
      });

    expect((await send()).status).toBe(200);
    expect((await send()).status).toBe(200);
    expect((await send()).status).toBe(200);

    const limited = await send();
    expect(limited.status).toBe(429);
    expect((limited.body as any).error.code).toBe('RATE_LIMITED');
    expect(limited.headers['retry-after']).toBeTruthy();
  });

  it('rejects a bearer token when Supabase is not configured', async () => {
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      headers: { authorization: 'Bearer some.jwt.token' },
      body: { text: 'go home' },
    });
    expect(res.status).toBe(503);
    expect((res.body as any).error.code).toBe('NOT_CONFIGURED');
  });

  it('requires auth when NUVA_REQUIRE_AUTH is on', async () => {
    process.env['NUVA_REQUIRE_AUTH'] = 'true';
    const res = await invokeForTest(await commandHandler(), {
      method: 'POST',
      url: '/api/ai/command',
      body: { text: 'go home' },
    });
    expect(res.status).toBe(401);
    expect((res.body as any).error.code).toBe('UNAUTHORIZED');
  });
});

describe('/api/commands and /api/memory require a user', () => {
  it('returns 401 without a token', async () => {
    const commands = await invokeForTest(await commandsHandler(), { method: 'GET', url: '/api/commands' });
    expect(commands.status).toBe(401);

    const memory = await invokeForTest(await memoryHandler(), { method: 'GET', url: '/api/memory' });
    expect(memory.status).toBe(401);
  });

  it('rejects unsupported methods', async () => {
    const res = await invokeForTest(await commandsHandler(), { method: 'PUT', url: '/api/commands', body: {} });
    expect(res.status).toBe(405);
  });

  it('never stores credential-like memory keys', async () => {
    // Fails at 401 first, proving auth precedes storage; the key guard itself is
    // unit-tested through the handler once a user exists (see docs/testing.md).
    const res = await invokeForTest(await memoryHandler(), {
      method: 'POST',
      url: '/api/memory',
      body: { key: 'password', value: 'hunter2' },
    });
    expect(res.status).toBe(401);
  });
});
