/**
 * POST /api/ai/command/stream tests: the SSE variant of the command endpoint.
 *
 * Covers: SSE content type and event ordering, parity of the final `result`
 * event with /api/ai/command, JSON errors for bad bodies (the stream only
 * starts after validation + auth), and in-stream `error` events.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { invokeForTest } from '../dev/vercel-shim';
import { resetRateLimits } from '../lib/ratelimit';

const ORIGINAL_ENV = { ...process.env };

async function streamHandler() {
  return (await import('../api/ai/command/stream')).default;
}
async function commandHandler() {
  return (await import('../api/ai/command')).default;
}

beforeEach(() => {
  resetRateLimits();
  delete process.env['GROQ_API_KEY'];
  delete process.env['SUPABASE_URL'];
  delete process.env['SUPABASE_ANON_KEY'];
  delete process.env['SUPABASE_SERVICE_ROLE_KEY'];
  delete process.env['UPSTASH_REDIS_REST_URL'];
  delete process.env['UPSTASH_REDIS_REST_TOKEN'];
  process.env['NUVA_LOG_LEVEL'] = 'error';
  process.env['NUVA_REQUIRE_AUTH'] = 'false';
  process.env['NUVA_ALLOW_FALLBACK_PARSER'] = 'true';
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  vi.restoreAllMocks();
});

function eventAt(events: Array<{ event: string; data: Record<string, any> }>, index: number | 'last'): { event: string; data: Record<string, any> } {
  const item = index === 'last' ? events.at(-1) : events[index];
  if (item === undefined) throw new Error(`missing SSE event at ${String(index)}`);
  return item;
}

function parseSse(raw: string): Array<{ event: string; data: Record<string, any> }> {
  return raw
    .split('\n\n')
    .filter((block) => block.trim().length > 0)
    .map((block) => {
      const eventLine = block.split('\n').find((line) => line.startsWith('event: '));
      const dataLine = block.split('\n').find((line) => line.startsWith('data: '));
      return {
        event: eventLine?.slice('event: '.length).trim() ?? '',
        data: dataLine ? (JSON.parse(dataLine.slice('data: '.length)) as Record<string, any>) : {},
      };
    });
}

describe('POST /api/ai/command/stream', () => {
  it('emits stage events then the same result as /api/ai/command', async () => {
    const res = await invokeForTest(await streamHandler(), {
      method: 'POST',
      url: '/api/ai/command/stream',
      body: { text: 'Nuva YouTube open koro.' },
    });

    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toContain('text/event-stream');
    expect(res.headers['cache-control']).toContain('no-store');

    const events = parseSse(res.raw);
    const accepted = eventAt(events, 0);
    expect(accepted.event).toBe('stage');
    expect(accepted.data.stage).toBe('accepted');
    expect(accepted.data.request_id).toBeTruthy();

    const interpreting = eventAt(events, 1);
    expect(interpreting.event).toBe('stage');
    expect(interpreting.data.stage).toBe('interpreting');
    expect(interpreting.data.source).toBe('fallback'); // no GROQ_API_KEY here

    const result = eventAt(events, 'last');
    expect(result.event).toBe('result');
    expect(result.data.ok).toBe(true);
    expect(result.data.result.intent).toBe('OPEN_APP');
    expect(result.data.result.action.app).toBe('youtube');
    expect(result.data.meta.source).toBe('fallback');
  });

  it('matches the non-streaming endpoint exactly on the result payload', async () => {
    const [streamed, plain] = await Promise.all([
      invokeForTest(await streamHandler(), {
        method: 'POST',
        url: '/api/ai/command/stream',
        body: { text: 'Nuva YouTube open koro.' },
      }),
      invokeForTest(await commandHandler(), {
        method: 'POST',
        url: '/api/ai/command',
        body: { text: 'Nuva YouTube open koro.' },
      }),
    ]);

    const streamResult = eventAt(parseSse(streamed.raw), 'last').data;
    const plainResult = plain.body as Record<string, any>;
    expect(streamResult['input']).toEqual(plainResult['input']);
    expect(streamResult['result']).toEqual(plainResult['result']);
  });

  it('still answers JSON 400 for a bad body (stream has not started yet)', async () => {
    const res = await invokeForTest(await streamHandler(), {
      method: 'POST',
      url: '/api/ai/command/stream',
      body: {},
    });
    expect(res.status).toBe(400);
    expect((res.body as Record<string, any>).error.code).toBe('BAD_REQUEST');
    expect(res.headers['content-type'] ?? '').not.toContain('text/event-stream');
  });

  it('carries pipeline failures inside the stream as error events', async () => {
    // A command the offline parser cannot match, with the fallback parser
    // disabled: the honest NOT_CONFIGURED failure must arrive as an SSE event.
    process.env['NUVA_ALLOW_FALLBACK_PARSER'] = 'false';
    process.env['NUVA_RATE_LIMIT_PER_MIN'] = '1000';

    const res = await invokeForTest(await streamHandler(), {
      method: 'POST',
      url: '/api/ai/command/stream',
      body: { text: 'Nuva amake ekta kobita likhe dao.' },
    });

    expect(res.status).toBe(200); // the stream itself opened fine
    const errorEvent = eventAt(parseSse(res.raw), 'last');
    expect(errorEvent.event).toBe('error');
    expect(errorEvent.data.ok).toBe(false);
    expect(errorEvent.data.error.code).toBe('NOT_CONFIGURED');
    expect(errorEvent.data.error.speech.length).toBeGreaterThan(0);
  });

  it('rejects unsupported methods', async () => {
    const res = await invokeForTest(await streamHandler(), { method: 'GET', url: '/api/ai/command/stream' });
    expect(res.status).toBe(405);
  });
});
