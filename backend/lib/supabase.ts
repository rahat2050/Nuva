/**
 * Supabase clients (§13). Two distinct clients, never mixed up:
 *
 *   anon client    — verifies user JWTs. Subject to Row Level Security.
 *   service client — server-side writes. BYPASSES RLS, so it is only ever used
 *                    with an explicit user_id that came from a verified JWT.
 *
 * Sessions are never persisted: serverless instances are shared between users
 * and a leaked session would be a cross-user data leak.
 *
 * HARDENED: All client creation and fetch calls are wrapped in try/catch to
 * avoid FUNCTION_INVOCATION_FAILED when Supabase SDK or fetch is unavailable.
 */
import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { getEnv, supabaseConfigured, supabaseWritable, type NuvaEnv } from './env.js';
import { NuvaError } from './errors.js';
import type { DependencyCheck } from '../types/api.js';

const CLIENT_OPTIONS = {
  auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  global: { headers: { 'X-Client-Info': 'nuva-backend/1.0.0' } },
} as const;

let cachedAnon: { url: string; client: SupabaseClient } | null = null;
let cachedService: { url: string; client: SupabaseClient } | null = null;

export function getAnonClient(env: NuvaEnv = getEnv()): SupabaseClient {
  if (!supabaseConfigured(env) || env.supabaseUrl === null || env.supabaseAnonKey === null) {
    throw new NuvaError('NOT_CONFIGURED', 'SUPABASE_URL and SUPABASE_ANON_KEY are required');
  }
  if (cachedAnon && cachedAnon.url === env.supabaseUrl) return cachedAnon.client;
  try {
    const client = createClient(env.supabaseUrl, env.supabaseAnonKey, CLIENT_OPTIONS);
    cachedAnon = { url: env.supabaseUrl, client };
    return client;
  } catch (err) {
    throw new NuvaError('NOT_CONFIGURED', `Failed to create Supabase anon client: ${err instanceof Error ? err.message : 'unknown'}`, {
      cause: err,
    });
  }
}

export function getServiceClient(env: NuvaEnv = getEnv()): SupabaseClient {
  if (!supabaseWritable(env) || env.supabaseUrl === null || env.supabaseServiceRoleKey === null) {
    throw new NuvaError('NOT_CONFIGURED', 'SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required');
  }
  if (cachedService && cachedService.url === env.supabaseUrl) return cachedService.client;
  try {
    const client = createClient(env.supabaseUrl, env.supabaseServiceRoleKey, CLIENT_OPTIONS);
    cachedService = { url: env.supabaseUrl, client };
    return client;
  } catch (err) {
    throw new NuvaError('NOT_CONFIGURED', `Failed to create Supabase service client: ${err instanceof Error ? err.message : 'unknown'}`, {
      cause: err,
    });
  }
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
 * Health probe. Checks REST reachability and, when the service role key is
 * available, whether the migrations have actually been applied.
 */
export async function pingSupabase(env: NuvaEnv = getEnv()): Promise<DependencyCheck> {
  if (!supabaseConfigured(env) || env.supabaseUrl === null || env.supabaseAnonKey === null) {
    return { ok: false, status: 'not_configured', latency_ms: null, detail: 'SUPABASE_URL / SUPABASE_ANON_KEY not set' };
  }

  const fetchFn = getFetch();
  if (!fetchFn) {
    return { ok: false, status: 'error', latency_ms: null, detail: 'fetch not available in this runtime' };
  }

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
    }, 5_000);
  } catch {
    // no timeout support
  }

  const startedAt = Date.now();

  try {
    const response = await fetchFn(`${env.supabaseUrl.replace(/\/+$/, '')}/rest/v1/`, {
      headers: { apikey: env.supabaseAnonKey, Authorization: `Bearer ${env.supabaseAnonKey}` },
      ...(controller ? { signal: controller.signal } : {}),
    });
    const latency = Date.now() - startedAt;

    if (response.status >= 500) {
      return { ok: false, status: 'error', latency_ms: latency, detail: `REST endpoint returned ${response.status}` };
    }

    if (!supabaseWritable(env)) {
      return { ok: true, status: 'ok', latency_ms: latency, detail: 'REST reachable (no service role key: schema not checked)' };
    }

    try {
      const { error } = await getServiceClient(env).from('profiles').select('id', { count: 'exact', head: true });
      if (error) {
        return {
          ok: false,
          status: 'error',
          latency_ms: Date.now() - startedAt,
          detail: `REST reachable but \`profiles\` query failed — run the migrations in supabase/migrations (${error.code ?? 'unknown'})`,
        };
      }
    } catch (err) {
      return {
        ok: false,
        status: 'error',
        latency_ms: Date.now() - startedAt,
        detail: `REST reachable but schema check failed: ${err instanceof Error ? err.message : 'unknown'}`,
      };
    }

    return { ok: true, status: 'ok', latency_ms: Date.now() - startedAt, detail: 'REST reachable, schema present' };
  } catch (err) {
    const aborted = err instanceof Error && err.name === 'AbortError';
    return {
      ok: false,
      status: 'error',
      latency_ms: Date.now() - startedAt,
      detail: aborted ? 'timed out after 5000ms' : `request failed: ${err instanceof Error ? err.message : 'unknown'}`,
    };
  } finally {
    if (timer) clearTimeout(timer);
  }
}
