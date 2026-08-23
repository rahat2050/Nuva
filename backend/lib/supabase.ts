/**
 * Supabase clients (§13). Two distinct clients, never mixed up:
 *
 *   anon client    — verifies user JWTs. Subject to Row Level Security.
 *   service client — server-side writes. BYPASSES RLS, so it is only ever used
 *                    with an explicit user_id that came from a verified JWT.
 *
 * Sessions are never persisted: serverless instances are shared between users
 * and a leaked session would be a cross-user data leak.
 */
import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import { getEnv, supabaseConfigured, supabaseWritable, type NuvaEnv } from './env';
import { NuvaError } from './errors';
import type { DependencyCheck } from '../types/api';

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
  const client = createClient(env.supabaseUrl, env.supabaseAnonKey, CLIENT_OPTIONS);
  cachedAnon = { url: env.supabaseUrl, client };
  return client;
}

export function getServiceClient(env: NuvaEnv = getEnv()): SupabaseClient {
  if (!supabaseWritable(env) || env.supabaseUrl === null || env.supabaseServiceRoleKey === null) {
    throw new NuvaError('NOT_CONFIGURED', 'SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required');
  }
  if (cachedService && cachedService.url === env.supabaseUrl) return cachedService.client;
  const client = createClient(env.supabaseUrl, env.supabaseServiceRoleKey, CLIENT_OPTIONS);
  cachedService = { url: env.supabaseUrl, client };
  return client;
}

/**
 * Health probe. Checks REST reachability and, when the service role key is
 * available, whether the migrations have actually been applied.
 */
export async function pingSupabase(env: NuvaEnv = getEnv()): Promise<DependencyCheck> {
  if (!supabaseConfigured(env) || env.supabaseUrl === null || env.supabaseAnonKey === null) {
    return { ok: false, status: 'not_configured', latency_ms: null, detail: 'SUPABASE_URL / SUPABASE_ANON_KEY not set' };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5_000);
  const startedAt = Date.now();

  try {
    const response = await fetch(`${env.supabaseUrl.replace(/\/+$/, '')}/rest/v1/`, {
      headers: { apikey: env.supabaseAnonKey, Authorization: `Bearer ${env.supabaseAnonKey}` },
      signal: controller.signal,
    });
    const latency = Date.now() - startedAt;

    if (response.status >= 500) {
      return { ok: false, status: 'error', latency_ms: latency, detail: `REST endpoint returned ${response.status}` };
    }

    if (!supabaseWritable(env)) {
      return { ok: true, status: 'ok', latency_ms: latency, detail: 'REST reachable (no service role key: schema not checked)' };
    }

    const { error } = await getServiceClient(env).from('profiles').select('id', { count: 'exact', head: true });
    if (error) {
      return {
        ok: false,
        status: 'error',
        latency_ms: Date.now() - startedAt,
        detail: `REST reachable but \`profiles\` query failed — run the migrations in supabase/migrations (${error.code ?? 'unknown'})`,
      };
    }

    return { ok: true, status: 'ok', latency_ms: Date.now() - startedAt, detail: 'REST reachable, schema present' };
  } catch (err) {
    const aborted = err instanceof Error && err.name === 'AbortError';
    return {
      ok: false,
      status: 'error',
      latency_ms: Date.now() - startedAt,
      detail: aborted ? 'timed out after 5000ms' : 'request failed',
    };
  } finally {
    clearTimeout(timer);
  }
}
