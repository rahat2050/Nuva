/**
 * /api/screenshots tests: the signed Cloudinary direct-upload grant.
 *
 * Covers: unconfigured → 503, anonymous → 401, happy path with a deterministic
 * SHA-1 signature recomputed independently, and the §12 guarantee that the API
 * secret never appears in any response.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createHash } from 'node:crypto';
import { invokeForTest } from '../dev/vercel-shim';

const ORIGINAL_ENV = { ...process.env };

const USER_ID = '11111111-1111-4111-8111-111111111111';
const API_SECRET = 'test-cloudinary-secret';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

async function screenshotsHandler() {
  return (await import('../api/screenshots/index')).default;
}

beforeEach(() => {
  delete process.env['GROQ_API_KEY'];
  delete process.env['CLOUDINARY_CLOUD_NAME'];
  delete process.env['CLOUDINARY_API_KEY'];
  delete process.env['CLOUDINARY_API_SECRET'];
  process.env['NUVA_LOG_LEVEL'] = 'error';
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const AUTH = { authorization: 'Bearer fake-access-token' };

/** Simulates a signed-in user without touching the network. */
function stubSupabaseUser(): void {
  process.env['SUPABASE_URL'] = 'http://supabase.local';
  process.env['SUPABASE_ANON_KEY'] = 'test-anon-key';
  process.env['SUPABASE_SERVICE_ROLE_KEY'] = 'test-service-key';
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => jsonResponse({ id: USER_ID, email: 'test@example.com', aud: 'authenticated' })),
  );
}

function configureCloudinary(): void {
  process.env['CLOUDINARY_CLOUD_NAME'] = 'nuva-test';
  process.env['CLOUDINARY_API_KEY'] = '987654321';
  process.env['CLOUDINARY_API_SECRET'] = API_SECRET;
}

describe('POST /api/screenshots', () => {
  it('rejects methods other than POST', async () => {
    const res = await invokeForTest(await screenshotsHandler(), { method: 'GET', url: '/api/screenshots' });
    expect(res.status).toBe(405);
  });

  it('requires a signed-in user (401 when anonymous)', async () => {
    const res = await invokeForTest(await screenshotsHandler(), { method: 'POST', url: '/api/screenshots', body: {} });
    expect(res.status).toBe(401);
    expect((res.body as Record<string, any>).error.code).toBe('UNAUTHORIZED');
  });

  it('reports 503 NOT_CONFIGURED when Cloudinary env vars are missing', async () => {
    stubSupabaseUser();
    const res = await invokeForTest(await screenshotsHandler(), { method: 'POST', url: '/api/screenshots', headers: AUTH, body: {} });
    expect(res.status).toBe(503);
    expect((res.body as Record<string, any>).error.code).toBe('NOT_CONFIGURED');
  });

  it('issues a grant with a verifiable Cloudinary signature', async () => {
    stubSupabaseUser();
    configureCloudinary();

    const res = await invokeForTest(await screenshotsHandler(), { method: 'POST', url: '/api/screenshots', headers: AUTH, body: {} });
    expect(res.status).toBe(200);

    const upload = (res.body as Record<string, any>).upload;
    expect(upload.cloud_name).toBe('nuva-test');
    expect(upload.api_key).toBe('987654321');
    expect(upload.folder).toBe(`nuva/${USER_ID}/screenshots`);
    expect(upload.upload_url).toBe('https://api.cloudinary.com/v1_1/nuva-test/image/upload');
    expect(upload.expires_at).toBe(upload.timestamp + 300);

    // Recompute the signature exactly the way Cloudinary documents it.
    const expected = createHash('sha1')
      .update(`folder=${upload.folder}&timestamp=${upload.timestamp}${API_SECRET}`)
      .digest('hex');
    expect(upload.signature).toBe(expected);
  });

  it('never leaks the API secret in the response', async () => {
    stubSupabaseUser();
    configureCloudinary();

    const res = await invokeForTest(await screenshotsHandler(), { method: 'POST', url: '/api/screenshots', headers: AUTH, body: {} });
    expect(res.raw).not.toContain(API_SECRET);
  });
});
