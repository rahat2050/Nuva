/**
 * POST /api/ai/command/stream — the SSE (Server-Sent Events) variant of
 * /api/ai/command (roadmap follow-up "Streaming to cut perceived latency").
 *
 * Same request body, same validation, same pipeline, same final JSON — but the
 * client receives progress events the moment they happen instead of waiting in
 * silence for the whole round-trip:
 *
 *   event: stage   data: {"stage":"accepted","request_id":"…"}
 *   event: stage   data: {"stage":"interpreting","source":"groq|fallback"}
 *   event: result  data: {…the exact CommandResponse from /api/ai/command…}
 *   [on failure] event: error data: {…the exact ApiErrorBody envelope…}
 *
 * Why stages instead of token streaming: the action must be validated as a
 * whole before anything is executed (§10), so partial model output could never
 * be acted on. Stage events give the app an instant "accepted / thinking"
 * signal for its voice and UI, which is where the perceived latency lives.
 * Clients that ignore SSE simply keep using /api/ai/command — the contract is
 * frozen either way.
 */

export const config = {
  maxDuration: 10,
};

import { defineHandler, type ApiContext } from '../../../lib/http.js';
import { resolveIdentity } from '../../../lib/auth.js';
import { interpretCommand } from '../../../lib/pipeline.js';
import { readCommandRequest } from '../../../lib/commandRequest.js';
import { toNuvaError } from '../../../lib/errors.js';
import { groqConfigured } from '../../../lib/env.js';

function writeSse(ctx: ApiContext, event: string, data: unknown): void {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  try {
    (ctx.res as unknown as { write?: (chunk: string) => unknown }).write?.(payload);
  } catch {
    // A failed write must never break the pipeline result below.
  }
}

export default defineHandler({
  name: 'ai/command/stream',
  methods: ['POST'],
  rateLimit: true,
  handler: async (ctx) => {
    const { req, body, logger, requestId, env, language } = ctx;
    // Parse + auth BEFORE the stream starts, so bad requests still get the
    // normal JSON error envelope with the right status code.
    const request = readCommandRequest(body);
    const identity = await resolveIdentity(req, logger, { bodyDeviceId: request.device_id, env });

    // SSE begins. These headers replace the default JSON response.
    try {
      ctx.res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
      ctx.res.setHeader('Cache-Control', 'no-store, max-age=0');
      ctx.res.setHeader('X-Accel-Buffering', 'no');
      ctx.res.setHeader('Connection', 'keep-alive');
    } catch {
      // mocked responses may not accept headers
    }

    writeSse(ctx, 'stage', { stage: 'accepted', request_id: requestId });
    writeSse(ctx, 'stage', { stage: 'interpreting', source: groqConfigured(env) ? 'groq' : 'fallback' });

    try {
      const response = await interpretCommand({ request, identity, logger, requestId, env });
      writeSse(ctx, 'result', response);
    } catch (err) {
      // The headers are already sent, so the error must travel inside the
      // stream — using the exact same shape as the JSON error envelope (§24).
      const error = toNuvaError(err);
      writeSse(ctx, 'error', {
        ok: false,
        request_id: requestId,
        error: { code: error.code, message: error.message, speech: error.speech(language) },
      });
      try {
        logger.warn('stream request rejected', { code: error.code, error: error.message });
      } catch {
        // ignore logger failure
      }
    }

    // Tell lib/http.ts that this response has been fully written by us.
    try {
      (ctx.res as unknown as { __nuvaStreamComplete?: boolean }).__nuvaStreamComplete = true;
    } catch {
      // ignore
    }
    try {
      (ctx.res as unknown as { end?: (body?: unknown) => unknown }).end?.();
    } catch {
      // ignore
    }
    return { status: 200, body: { ok: true } };
  },
});
