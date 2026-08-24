/**
 * /api/devices tests: registration against a stubbed Supabase REST backend.
 *
 * The stub speaks the PostgREST shapes supabase-js actually exchanges:
 *   GET  /rest/v1/devices → array of rows (maybeSingle resolves client-side)
 *   POST /rest/v1/devices → inserted row object
 *   PATCH /rest/v1/devices → updated row object
 * plus GET /auth/v1/user → the verified user, so requireUser() passes.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { invokeForTest } from '../dev/vercel-shim';

const ORIGINAL_ENV = { ...process.env };

const USER_ID = '22222222-2222-4222-8222-222222222222';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function deviceRow(name = 'Pixel 7', version = '14'): Record<string, unknown> {
  return {
    id: '33333333-3333-4333-8333-333333333333',
    device_name: name,
    android_version: version,
    created_at: '2026-08-24T00:00:00.000Z',
  };
}

const AUTH = { authorization: 'Bearer fake-access-token' };

async function devicesHandler() {
  return (await import('../api/devices/index')).default;
}

beforeEach(() => {
  delete process.env['GROQ_API_KEY'];
  delete process.env['SUPABASE_URL'];
  delete process.env['SUPABASE_ANON_KEY'];
  delete process.env['SUPABASE_SERVICE_ROLE_KEY'];
  process.env['NUVA_LOG_LEVEL'] = 'error';
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function configureSupabase(options: { existing?: unknown; insert?: unknown; listed?: unknown[] } = {}): void {
  process.env['SUPABASE_URL'] = 'http://supabase.local';
  process.env['SUPABASE_ANON_KEY'] = 'test-anon-key';
  process.env['SUPABASE_SERVICE_ROLE_KEY'] = 'test-service-key';

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: unknown, init?: { method?: string }) => {
      const url = typeof input === 'string' ? input : String((input as { url?: string })?.url ?? '');
      const method = (init?.method ?? 'GET').toUpperCase();

      if (url.includes('/auth/v1/user')) {
        return jsonResponse({ id: USER_ID, email: 'test@example.com', aud: 'authenticated' });
      }
      if (url.includes('/rest/v1/devices')) {
        if (method === 'GET') return jsonResponse(options.listed ?? (options.existing ? [options.existing] : []));
        if (method === 'POST') return jsonResponse(options.insert ?? deviceRow(), 201);
        if (method === 'PATCH') return jsonResponse(options.insert ?? deviceRow());
      }
      return jsonResponse({ message: 'not found' }, 404);
    }),
  );
}

describe('GET/POST /api/devices', () => {
  it('rejects unsupported methods with 405', async () => {
    const res = await invokeForTest(await devicesHandler(), { method: 'PUT', url: '/api/devices', body: {} });
    expect(res.status).toBe(405);
    expect(res.headers['allow']).toContain('GET');
    expect(res.headers['allow']).toContain('POST');
  });

  it('requires a signed-in user (401 when anonymous)', async () => {
    const get = await invokeForTest(await devicesHandler(), { method: 'GET', url: '/api/devices' });
    expect(get.status).toBe(401);

    const post = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      body: { device_name: 'Pixel 7' },
    });
    expect(post.status).toBe(401);
  });

  it('registers a new device (201) when none exists yet', async () => {
    configureSupabase({ insert: deviceRow('Pixel 7', '14') });

    const res = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      headers: AUTH,
      body: { device_name: 'Pixel 7', android_version: '14' },
    });

    expect(res.status).toBe(201);
    const body = res.body as Record<string, any>;
    expect(body.ok).toBe(true);
    expect(body.device.device_name).toBe('Pixel 7');
    expect(body.device.android_version).toBe('14');
  });

  it('returns the existing device instead of duplicating it', async () => {
    configureSupabase({ existing: deviceRow('Pixel 7', '13'), insert: deviceRow('Pixel 7', '14') });

    const res = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      headers: AUTH,
      body: { device_name: 'Pixel 7', android_version: '14' },
    });

    expect(res.status).toBe(201);
    expect((res.body as Record<string, any>).device.device_name).toBe('Pixel 7');
  });

  it('lists the user’s devices', async () => {
    configureSupabase({ listed: [deviceRow('Pixel 7'), deviceRow('Galaxy S23')] });

    const res = await invokeForTest(await devicesHandler(), { method: 'GET', url: '/api/devices', headers: AUTH });
    expect(res.status).toBe(200);
    const body = res.body as Record<string, any>;
    expect(body.ok).toBe(true);
    expect(body.count).toBe(2);
    expect(body.devices[1].device_name).toBe('Galaxy S23');
  });

  it('validates device_name (missing, empty, too long)', async () => {
    configureSupabase();

    const missing = await invokeForTest(await devicesHandler(), { method: 'POST', url: '/api/devices', headers: AUTH, body: {} });
    expect(missing.status).toBe(400);

    const empty = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      headers: AUTH,
      body: { device_name: '   ' },
    });
    expect(empty.status).toBe(400);

    const tooLong = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      headers: AUTH,
      body: { device_name: 'x'.repeat(121) },
    });
    expect(tooLong.status).toBe(400);
    expect((tooLong.body as Record<string, any>).error.code).toBe('BAD_REQUEST');
  });

  it('rejects control characters in device_name', async () => {
    configureSupabase();
    const res = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      headers: AUTH,
      body: { device_name: 'Bad\u0000Name' },
    });
    expect(res.status).toBe(400);
  });

  it('reports 503 when persistence is not configured', async () => {
    // Signed in (URL + anon key) but no service role key → persistence disabled.
    process.env['SUPABASE_URL'] = 'http://supabase.local';
    process.env['SUPABASE_ANON_KEY'] = 'test-anon-key';
    delete process.env['SUPABASE_SERVICE_ROLE_KEY'];
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ id: USER_ID, email: 'test@example.com', aud: 'authenticated' })),
    );

    const res = await invokeForTest(await devicesHandler(), {
      method: 'POST',
      url: '/api/devices',
      headers: AUTH,
      body: { device_name: 'Pixel 7' },
    });
    expect(res.status).toBe(503);
    expect((res.body as Record<string, any>).error.code).toBe('NOT_CONFIGURED');
  });
});
