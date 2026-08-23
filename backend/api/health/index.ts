/**
 * GET /api/health
 *
 * Cheap by default: reports configuration only, so uptime monitors cost nothing.
 * GET /api/health?deep=1 additionally round-trips Groq and Supabase — use it
 * after deploying to prove the PHRASE 1 exit criteria.
 *
 * Never returns secret values, only booleans describing their presence (§12).
 */
import { defineHandler, ok } from '../../lib/http';
import { envSummary, type NuvaEnv } from '../../lib/env';
import { pingGroq } from '../../lib/groq';
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
  } catch {
    return {
      ok: false,
      status: 'error',
      latency_ms: null,
      detail: 'Supabase health check could not be initialized',
    };
  }
}

export default defineHandler({
  name: 'health',
  methods: ['GET'],
  handler: async ({ requestId, query, env, logger }) => {
    const config = envSummary(env);
    const deep = query['deep'] === '1' || query['deep'] === 'true';

    const response: HealthResponse = {
      ok: true,
      service: 'nuva-backend',
      version: VERSION,
      phase: 1,
      time: new Date().toISOString(),
      request_id: requestId,
      config,
    };

    if (deep) {
      const [groq, supabase] = await Promise.all([pingGroq(env), checkSupabase(env)]);
      response.checks = { groq, supabase };
      // A dependency that is deliberately not configured is not a failure;
      // a configured dependency that cannot be reached is.
      response.ok = groq.status !== 'error' && supabase.status !== 'error';
      logger.info('deep health check', {
        groq: groq.status,
        supabase: supabase.status,
        groq_latency_ms: groq.latency_ms,
        supabase_latency_ms: supabase.latency_ms,
      });
    }

    return ok(response);
  },
});
