/**
 * Groq integration tests against a mock Groq server.
 *
 * These exercise the real HTTP client (headers, body shape, retries, model
 * fallback, timeouts, error mapping) without needing a live GROQ_API_KEY, so the
 * integration is verifiable in CI and by any developer cloning the repo.
 *
 * The decommissioned-model test matters in practice: Groq shut down
 * llama-3.3-70b-versatile on 2026-08-16, and NUVA must survive that class of
 * event by switching to GROQ_FALLBACK_MODEL instead of going dark.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { groqChatJson, pingGroq } from '../lib/groq';
import { createLogger } from '../lib/logger';
import type { NuvaEnv } from '../lib/env';

interface CapturedRequest {
  path: string;
  method: string;
  headers: Record<string, string | string[] | undefined>;
  body: Record<string, unknown>;
}

let server: Server;
let baseUrl: string;
let requests: CapturedRequest[] = [];

/** Queue of responses; each request pops the next one. */
let responses: Array<{ status: number; body: unknown; delayMs?: number }> = [];

function chatCompletion(content: string, model = 'openai/gpt-oss-20b') {
  return {
    id: 'chatcmpl-test',
    object: 'chat.completion',
    model,
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 120, completion_tokens: 30, total_tokens: 150 },
  };
}

beforeAll(async () => {
  server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk) => chunks.push(Buffer.from(chunk)));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      let body: Record<string, unknown> = {};
      try {
        body = raw.length > 0 ? JSON.parse(raw) : {};
      } catch {
        body = { unparseable: raw };
      }
      requests.push({ path: req.url ?? '', method: req.method ?? '', headers: req.headers, body });

      const next = responses.shift() ?? { status: 200, body: chatCompletion('{"intent":"GO_HOME"}') };
      const send = () => {
        res.statusCode = next.status;
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify(next.body));
      };
      if (next.delayMs) setTimeout(send, next.delayMs);
      else send();
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as AddressInfo;
  baseUrl = `http://127.0.0.1:${address.port}`;
});

afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

beforeEach(() => {
  requests = [];
  responses = [];
});

afterEach(() => {
  expect(responses, 'unused mock responses left over').toHaveLength(0);
});

function env(overrides: Partial<NuvaEnv> = {}): NuvaEnv {
  return {
    groqApiKey: 'test-key-abc123',
    groqModel: 'openai/gpt-oss-20b',
    groqFallbackModel: 'openai/gpt-oss-120b',
    groqBaseUrl: baseUrl,
    groqTimeoutMs: 3_000,
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
    ...overrides,
  };
}

const logger = createLogger({ test: 'groq' });

describe('groqChatJson — request shape', () => {
  it('calls the chat completions endpoint with the documented parameters', async () => {
    responses.push({ status: 200, body: chatCompletion('{"intent":"GO_HOME","action":{"type":"GO_HOME"}}') });

    const result = await groqChatJson({ system: 'SYS', user: 'USER' }, logger, env());

    expect(result.content).toBe('{"intent":"GO_HOME","action":{"type":"GO_HOME"}}');
    expect(result.model).toBe('openai/gpt-oss-20b');
    expect(result.usage?.total_tokens).toBe(150);

    expect(requests).toHaveLength(1);
    const [request] = requests;
    expect(request?.path).toBe('/chat/completions');
    expect(request?.method).toBe('POST');
    expect(request?.headers['authorization']).toBe('Bearer test-key-abc123');
    expect(request?.body['model']).toBe('openai/gpt-oss-20b');
    expect(request?.body['temperature']).toBe(0.1);
    expect(request?.body['stream']).toBe(false);
    expect(request?.body['response_format']).toEqual({ type: 'json_object' });
    expect(request?.body['max_completion_tokens']).toBe(800);
    expect(request?.body['reasoning_effort']).toBe('low');
    expect(request?.body['messages']).toEqual([
      { role: 'system', content: 'SYS' },
      { role: 'user', content: 'USER' },
    ]);
  });

  it('omits reasoning_effort for non gpt-oss models', async () => {
    responses.push({ status: 200, body: chatCompletion('{}', 'qwen/qwen3.6-27b') });
    await groqChatJson({ system: 'S', user: 'U' }, logger, env({ groqModel: 'qwen/qwen3.6-27b' }));
    expect(requests[0]?.body['reasoning_effort']).toBeUndefined();
  });

  it('omits reasoning_effort when disabled by configuration', async () => {
    responses.push({ status: 200, body: chatCompletion('{}') });
    await groqChatJson({ system: 'S', user: 'U' }, logger, env({ groqReasoningEffort: null }));
    expect(requests[0]?.body['reasoning_effort']).toBeUndefined();
  });
});

describe('groqChatJson — resilience', () => {
  it('retries without reasoning_effort when the model rejects it', async () => {
    responses.push({
      status: 400,
      body: { error: { message: "Unsupported parameter: 'reasoning_effort'", type: 'invalid_request_error' } },
    });
    responses.push({ status: 200, body: chatCompletion('{"ok":true}') });

    const result = await groqChatJson({ system: 'S', user: 'U' }, logger, env());

    expect(result.content).toBe('{"ok":true}');
    expect(requests).toHaveLength(2);
    expect(requests[0]?.body['reasoning_effort']).toBe('low');
    expect(requests[1]?.body['reasoning_effort']).toBeUndefined();
  });

  it('switches to the fallback model when the primary is decommissioned', async () => {
    // Exactly what Groq returned for llama-3.3-70b-versatile after 2026-08-16.
    responses.push({
      status: 400,
      body: {
        error: {
          message: 'The model `llama-3.3-70b-versatile` has been decommissioned and is no longer supported.',
          type: 'invalid_request_error',
          code: 'model_decommissioned',
        },
      },
    });
    responses.push({ status: 200, body: chatCompletion('{"ok":true}', 'openai/gpt-oss-120b') });

    const result = await groqChatJson(
      { system: 'S', user: 'U' },
      logger,
      env({ groqModel: 'llama-3.3-70b-versatile' }),
    );

    expect(result.model).toBe('openai/gpt-oss-120b');
    expect(requests[0]?.body['model']).toBe('llama-3.3-70b-versatile');
    expect(requests[1]?.body['model']).toBe('openai/gpt-oss-120b');
  });

  it('retries once on a 5xx and then succeeds', async () => {
    responses.push({ status: 503, body: { error: { message: 'service unavailable' } } });
    responses.push({ status: 200, body: chatCompletion('{"ok":true}') });

    const result = await groqChatJson({ system: 'S', user: 'U' }, logger, env());
    expect(result.content).toBe('{"ok":true}');
    expect(requests).toHaveLength(2);
  });

  it('maps a bad key to NOT_CONFIGURED without retrying', async () => {
    responses.push({ status: 401, body: { error: { message: 'Invalid API Key' } } });

    await expect(groqChatJson({ system: 'S', user: 'U' }, logger, env())).rejects.toMatchObject({
      code: 'NOT_CONFIGURED',
    });
    expect(requests).toHaveLength(1);
  });

  it('maps upstream 429 to RATE_LIMITED after exhausting retries', async () => {
    responses.push({ status: 429, body: { error: { message: 'rate limit' } } });
    responses.push({ status: 429, body: { error: { message: 'rate limit' } } });
    responses.push({ status: 429, body: { error: { message: 'rate limit' } } });

    await expect(groqChatJson({ system: 'S', user: 'U' }, logger, env())).rejects.toMatchObject({
      code: 'RATE_LIMITED',
    });
    expect(requests).toHaveLength(3);
  });

  it('times out instead of hanging the request', async () => {
    responses.push({ status: 200, body: chatCompletion('{}'), delayMs: 400 });
    responses.push({ status: 200, body: chatCompletion('{}'), delayMs: 400 });
    responses.push({ status: 200, body: chatCompletion('{}'), delayMs: 400 });

    await expect(
      groqChatJson({ system: 'S', user: 'U' }, logger, env({ groqTimeoutMs: 80 })),
    ).rejects.toMatchObject({ code: 'UPSTREAM_TIMEOUT' });
  }, 10_000);

  it('rejects an empty completion', async () => {
    responses.push({ status: 200, body: chatCompletion('') });
    responses.push({ status: 200, body: chatCompletion('') });
    responses.push({ status: 200, body: chatCompletion('') });

    await expect(groqChatJson({ system: 'S', user: 'U' }, logger, env())).rejects.toMatchObject({
      code: 'AI_INVALID_OUTPUT',
    });
  });

  it('fails cleanly when no API key is configured', async () => {
    await expect(
      groqChatJson({ system: 'S', user: 'U' }, logger, env({ groqApiKey: null })),
    ).rejects.toMatchObject({ code: 'NOT_CONFIGURED' });
    expect(requests).toHaveLength(0);
  });
});

describe('pingGroq', () => {
  it('reports ok and confirms the configured model is available', async () => {
    responses.push({
      status: 200,
      body: { object: 'list', data: [{ id: 'openai/gpt-oss-20b' }, { id: 'openai/gpt-oss-120b' }] },
    });

    const check = await pingGroq(env());
    expect(check.ok).toBe(true);
    expect(check.status).toBe('ok');
    expect(check.detail).toContain('openai/gpt-oss-20b present');
    expect(requests[0]?.path).toBe('/models');
  });

  it('warns when the configured model is missing from the model list', async () => {
    responses.push({ status: 200, body: { object: 'list', data: [{ id: 'openai/gpt-oss-120b' }] } });

    const check = await pingGroq(env({ groqModel: 'llama-3.3-70b-versatile' }));
    expect(check.ok).toBe(true);
    expect(check.detail).toContain('WARNING');
  });

  it('reports not_configured without a key', async () => {
    const check = await pingGroq(env({ groqApiKey: null }));
    expect(check).toMatchObject({ ok: false, status: 'not_configured' });
  });

  it('reports an error for a failing upstream', async () => {
    responses.push({ status: 500, body: { error: 'boom' } });
    const check = await pingGroq(env());
    expect(check).toMatchObject({ ok: false, status: 'error' });
  });
});

describe('POST /api/ai/command against a mock Groq', () => {
  const ORIGINAL_ENV = { ...process.env };

  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
  });

  it('uses the real AI path end to end', async () => {
    const { invokeForTest } = await import('../dev/vercel-shim');
    const { resetRateLimits } = await import('../lib/ratelimit');
    resetRateLimits();

    process.env['GROQ_API_KEY'] = 'test-key-abc123';
    process.env['GROQ_BASE_URL'] = baseUrl;
    process.env['GROQ_MODEL'] = 'openai/gpt-oss-20b';
    process.env['NUVA_LOG_LEVEL'] = 'error';
    delete process.env['SUPABASE_URL'];

    responses.push({
      status: 200,
      body: chatCompletion(
        JSON.stringify({
          intent: 'SEND_MESSAGE',
          action: { type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'ami ashchi' },
          risk: 'medium',
          requires_confirmation: true,
          confidence: 0.93,
          speech: 'Rahim ke ei message ta pathabo?',
        }),
      ),
    });

    const handler = (await import('../api/ai/command')).default;
    const res = await invokeForTest(handler, {
      method: 'POST',
      url: '/api/ai/command',
      headers: { 'x-nuva-device-id': 'groq-integration' },
      body: { text: 'Nuva Rahim ke WhatsApp e message pathao je ami ashchi.' },
    });

    expect(res.status).toBe(200);
    const body = res.body as Record<string, any>;
    expect(body.meta.source).toBe('groq');
    expect(body.meta.model).toBe('openai/gpt-oss-20b');
    expect(body.result.intent).toBe('SEND_MESSAGE');
    expect(body.result.risk).toBe('medium');
    expect(body.result.requires_confirmation).toBe(true);
    expect(body.result.speech).toBe('Rahim ke ei message ta pathabo?');

    // The system prompt really did travel to the model.
    const sent = requests[0]?.body['messages'] as Array<{ role: string; content: string }>;
    expect(sent[0]?.content).toContain('ACTION CATALOGUE');
    expect(sent[1]?.content).toContain('User: Rahim ke WhatsApp e message pathao je ami ashchi.');
  });
});
