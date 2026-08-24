/**
 * Groq client (§4: Android → Vercel → Groq; the key never leaves the server).
 *
 * Uses native fetch rather than an SDK: fewer dependencies to audit, and full
 * control over timeouts, retries and model fallback (§26 — no unjustified
 * dependencies).
 *
 * Resilience built in:
 *   * hard timeout via AbortController (8s default, fits in Vercel 10s limit)
 *   * one retry on 429/5xx/network blips
 *   * automatic retry without `reasoning_effort` if the model rejects it
 *   * automatic switch to GROQ_FALLBACK_MODEL if the primary model is
 *     decommissioned — Groq retires models regularly (llama-3.3-70b-versatile
 *     was shut down 2026-08-16), so NUVA must survive that without a redeploy
 *
 * HARDENED: All fetch calls are wrapped to handle missing fetch global or
 * AbortController in edge-like environments, returning structured errors instead
 * of crashing.
 */
import { getEnv, groqConfigured, type NuvaEnv } from './env.js';
import { NuvaError } from './errors.js';
import type { Logger } from './logger.js';
import type { DependencyCheck } from '../types/api.js';

export interface GroqResult {
  content: string;
  model: string;
  latencyMs: number;
  usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number };
}

interface ChatChoice {
  message?: { content?: string | null };
  finish_reason?: string;
}

interface ChatResponse {
  model?: string;
  choices?: ChatChoice[];
  usage?: GroqResult['usage'];
}

const MAX_ATTEMPTS = 3;
const RETRY_DELAY_MS = 250;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** gpt-oss models accept `reasoning_effort`; other families may reject it. */
function supportsReasoningEffort(model: string): boolean {
  return model.startsWith('openai/gpt-oss');
}

function isDecommissioned(status: number, body: string): boolean {
  if (status !== 400 && status !== 404) return false;
  return /decommission|deprecat|does not exist|not found|model_not_found|no longer/i.test(body);
}

function rejectsReasoningEffort(status: number, body: string): boolean {
  return status === 400 && /reasoning_effort/i.test(body);
}

function isRetryableStatus(status: number): boolean {
  return status === 408 || status === 429 || status >= 500;
}

function getFetch(): typeof fetch | null {
  try {
    if (typeof globalThis.fetch === 'function') return globalThis.fetch.bind(globalThis);
    // Fallback: try to require node-fetch if available (shouldn't be needed in Node 18+)
    return null;
  } catch {
    return null;
  }
}

export async function groqChatJson(
  params: { system: string; user: string; maxTokens?: number },
  logger: Logger,
  env: NuvaEnv = getEnv(),
): Promise<GroqResult> {
  if (!groqConfigured(env) || env.groqApiKey === null) {
    throw new NuvaError('NOT_CONFIGURED', 'GROQ_API_KEY is not configured');
  }

  const fetchFn = getFetch();
  if (!fetchFn) {
    throw new NuvaError('AI_UNAVAILABLE', 'fetch is not available in this runtime');
  }

  let model = env.groqModel;
  let includeReasoning = env.groqReasoningEffort !== null;
  let lastError: NuvaError | null = null;

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
    const body: Record<string, unknown> = {
      model,
      messages: [
        { role: 'system', content: params.system },
        { role: 'user', content: params.user },
      ],
      temperature: 0.1,
      top_p: 1,
      max_completion_tokens: params.maxTokens ?? 800,
      response_format: { type: 'json_object' },
      stream: false,
    };
    if (includeReasoning && supportsReasoningEffort(model) && env.groqReasoningEffort) {
      body['reasoning_effort'] = env.groqReasoningEffort;
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
      }, env.groqTimeoutMs);
    } catch {
      // AbortController not available, proceed without timeout
    }

    const startedAt = Date.now();

    try {
      const response = await fetchFn(`${env.groqBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${env.groqApiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
        ...(controller ? { signal: controller.signal } : {}),
      });

      const latencyMs = Date.now() - startedAt;

      if (!response.ok) {
        const errorBody = (await response.text()).slice(0, 800);
        try {
          logger.warn('groq request failed', { attempt, model, status: response.status, latency_ms: latencyMs });
        } catch {
          // logger failure non-fatal
        }

        if (rejectsReasoningEffort(response.status, errorBody) && includeReasoning) {
          includeReasoning = false;
          continue;
        }
        if (isDecommissioned(response.status, errorBody) && model !== env.groqFallbackModel) {
          try {
            logger.warn('groq model unavailable, switching to fallback model', {
              from: model,
              to: env.groqFallbackModel,
            });
          } catch {
            // ignore
          }
          model = env.groqFallbackModel;
          continue;
        }
        if (response.status === 401 || response.status === 403) {
          throw new NuvaError('NOT_CONFIGURED', 'Groq rejected the API key', { details: { status: response.status } });
        }
        if (response.status === 429) {
          lastError = new NuvaError('RATE_LIMITED', 'Groq rate limit reached');
        } else {
          lastError = new NuvaError('AI_UNAVAILABLE', `Groq responded with ${response.status}`, {
            details: { status: response.status },
          });
        }
        if (isRetryableStatus(response.status) && attempt < MAX_ATTEMPTS) {
          await sleep(RETRY_DELAY_MS * attempt);
          continue;
        }
        throw lastError;
      }

      const payload = (await response.json()) as ChatResponse;
      const content = payload.choices?.[0]?.message?.content ?? '';
      if (content.trim().length === 0) {
        lastError = new NuvaError('AI_INVALID_OUTPUT', 'Groq returned an empty completion');
        if (attempt < MAX_ATTEMPTS) {
          await sleep(RETRY_DELAY_MS * attempt);
          continue;
        }
        throw lastError;
      }

      const result: GroqResult = {
        content,
        model: payload.model ?? model,
        latencyMs,
      };
      if (payload.usage) result.usage = payload.usage;

      try {
        logger.debug('groq completion received', {
          model: result.model,
          latency_ms: latencyMs,
          total_tokens: payload.usage?.total_tokens,
        });
      } catch {
        // ignore logger failure
      }
      return result;
    } catch (err) {
      if (err instanceof NuvaError) throw err;

      const aborted = err instanceof Error && (err.name === 'AbortError' || err.name === 'TimeoutError');
      lastError = aborted
        ? new NuvaError('UPSTREAM_TIMEOUT', `Groq timed out after ${env.groqTimeoutMs}ms`)
        : new NuvaError('AI_UNAVAILABLE', err instanceof Error ? err.message : 'Groq request failed', { cause: err });

      try {
        logger.warn('groq request errored', { attempt, model, aborted, error: lastError.message });
      } catch {
        // ignore
      }
      if (attempt < MAX_ATTEMPTS) {
        await sleep(RETRY_DELAY_MS * attempt);
        continue;
      }
      throw lastError;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  throw lastError ?? new NuvaError('AI_UNAVAILABLE', 'Groq request failed');
}

/** Lightweight reachability probe for /api/health?deep=1. Never leaks the key. */
export async function pingGroq(env: NuvaEnv = getEnv()): Promise<DependencyCheck> {
  if (!groqConfigured(env) || env.groqApiKey === null) {
    return { ok: false, status: 'not_configured', latency_ms: null, detail: 'GROQ_API_KEY is not set' };
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
    // ignore, no timeout
  }

  const startedAt = Date.now();

  try {
    const response = await fetchFn(`${env.groqBaseUrl}/models`, {
      headers: { Authorization: `Bearer ${env.groqApiKey}` },
      ...(controller ? { signal: controller.signal } : {}),
    });
    const latency = Date.now() - startedAt;
    if (!response.ok) {
      return { ok: false, status: 'error', latency_ms: latency, detail: `HTTP ${response.status}` };
    }
    const payload = (await response.json()) as { data?: Array<{ id?: string }> };
    const ids = (payload.data ?? []).map((m) => m.id).filter((id): id is string => typeof id === 'string');
    const modelAvailable = ids.includes(env.groqModel);
    return {
      ok: true,
      status: 'ok',
      latency_ms: latency,
      detail: modelAvailable
        ? `${ids.length} models available, ${env.groqModel} present`
        : `${ids.length} models available, WARNING: ${env.groqModel} not listed (fallback: ${env.groqFallbackModel})`,
    };
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
