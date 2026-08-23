/**
 * GET /api/health
 *
 * Cheap by default: reports configuration only, so uptime monitors cost nothing.
 * GET /api/health?deep=1 additionally round-trips Groq and Supabase — use it
 * after deploying to prove the PHRASE 1 exit criteria.
 *
 * Never returns secret values, only booleans describing their presence (§12).
 *
 * HARDENED: Every external call is wrapped in try/catch so that even if Groq
 * or Supabase SDK fails to load, the endpoint still returns 200 with a
 * structured error instead of FUNCTION_INVOCATION_FAILED.
 */

// Vercel explicit runtime config — ensures Node.js 22.x even if project
// defaults change. Also keeps Hobby plan compatible.
export const config = {
  runtime: 'nodejs24.x',
  maxDuration: 10,
  memory: 512,
};

import { defineHandler, ok } from '../../lib/http';
import { envSummary, type NuvaEnv } from '../../lib/env';
import type { DependencyCheck } from '../../types/api';
import type { HealthResponse } from '../../types/api';

const VERSION = '1.0.0';

/**
 * Supabase is not needed for the normal liveness endpoint. Loading it lazily
 * means an optional database SDK/configuration issue cannot turn a cheap health
 * check into a function-invocation failure. Deep health still reports such an
 * issue as a structured dependency error.
 */
async function checkSupabase(env: NuvaEnv): Promise<DependencyCheck> {
  try {
    const { pingSupabase } = await import('../../lib/supabase');
    return await pingSupabase(env);
  } catch (err) {
    return {
      ok: false,
      status: 'error',
      latency_ms: null,
      detail: `Supabase health check could not be initialized: ${err instanceof Error ? err.message : 'unknown error'}`,
    };
  }
}

async function checkGroq(env: NuvaEnv): Promise<DependencyCheck> {
  try {
    const { pingGroq } = await import('../../lib/groq');
    return await pingGroq(env);
  } catch (err) {
    return {
      ok: false,
      status: 'error',
      latency_ms: null,
      detail: `Groq health check could not be initialized: ${err instanceof Error ? err.message : 'unknown error'}`,
    };
  }
}

export default defineHandler({
  name: 'health',
  methods: ['GET'],
  handler: async ({ requestId, query, env, logger }) => {
    let configSummary;
    try {
      configSummary = envSummary(env);
    } catch {
      configSummary = {
        groq: { configured: false, model: 'openai/gpt-oss-20b', fallback_model: 'openai/gpt-oss-120b' },
        supabase: { configured: false, service_role: false },
        auth_required: false,
        persistence: false,
        fallback_parser: true,
      };
    }

    const deep = query['deep'] === '1' || query['deep'] === 'true';

    const response: HealthResponse = {
      ok: true,
      service: 'nuva-backend',
      version: VERSION,
      phase: 1,
      time: new Date().toISOString(),
      request_id: requestId,
      config: configSummary,
    };

    if (deep) {
      try {
        const [groq, supabase] = await Promise.all([checkGroq(env), checkSupabase(env)]);
        response.checks = { groq, supabase };
        // A dependency that is deliberately not configured is not a failure;
        // a configured dependency that cannot be reached is.
        response.ok = groq.status !== 'error' && supabase.status !== 'error';
        try {
          logger.info('deep health check', {
            groq: groq.status,
            supabase: supabase.status,
            groq_latency_ms: groq.latency_ms,
            supabase_latency_ms: supabase.latency_ms,
          });
        } catch {
          // logger failure is non-fatal
        }
      } catch (err) {
        // Even if Promise.all throws (shouldn't), return a safe response
        response.checks = {
          groq: {
            ok: false,
            status: 'error',
            latency_ms: null,
            detail: `Deep check failed: ${err instanceof Error ? err.message : 'unknown'}`,
          },
          supabase: {
            ok: false,
            status: 'error',
            latency_ms: null,
            detail: 'Deep check failed',
          },
        };
        response.ok = false;
      }
    }

    return ok(response);
  },
});
