/**
 * POST /api/ai/command — the single entry point the Android app uses to turn a
 * voice transcript into a validated, risk-classified action.
 *
 * Request  : { text, language?, device_id?, client_request_id?, context? }
 * Response : see types/api.ts → CommandResponse
 *
 * A request that is understood always returns HTTP 200, even when the answer is
 * "I can't do that" (intent UNSUPPORTED). Non-200 means the request itself could
 * not be processed (auth, rate limit, AI unreachable), which is what the Android
 * error handling in §24 keys off.
 */

export const config = {
  runtime: 'nodejs22.x',
  maxDuration: 10,
};

import { defineHandler, ok } from '../../lib/http';
import { resolveIdentity } from '../../lib/auth';
import { interpretCommand } from '../../lib/pipeline';
import { NuvaError } from '../../lib/errors';
import { MAX_COMMAND_CHARS } from '../../lib/normalize';
import type { CommandRequest } from '../../types/api';

function readRequest(body: Record<string, unknown>): CommandRequest {
  const text = body['text'];
  if (typeof text !== 'string') {
    throw new NuvaError('BAD_REQUEST', '`text` is required and must be a string');
  }
  if (text.length > MAX_COMMAND_CHARS) {
    throw new NuvaError('PAYLOAD_TOO_LARGE', `\`text\` must be at most ${MAX_COMMAND_CHARS} characters`);
  }

  const request: CommandRequest = { text };

  const language = body['language'];
  if (typeof language === 'string') {
    if (!['auto', 'bn', 'en', 'banglish'].includes(language)) {
      throw new NuvaError('BAD_REQUEST', '`language` must be auto, bn, en or banglish');
    }
    request.language = language as CommandRequest['language'];
  }

  const deviceId = body['device_id'];
  if (typeof deviceId === 'string') request.device_id = deviceId.slice(0, 128);

  const clientRequestId = body['client_request_id'];
  if (typeof clientRequestId === 'string') request.client_request_id = clientRequestId.slice(0, 128);

  const context = body['context'];
  if (context !== undefined) {
    if (context === null || typeof context !== 'object' || Array.isArray(context)) {
      throw new NuvaError('BAD_REQUEST', '`context` must be an object');
    }
    const { foreground_app: foregroundApp, screen_summary: screenSummary } = context as Record<string, unknown>;
    request.context = {};
    if (typeof foregroundApp === 'string') request.context.foreground_app = foregroundApp.slice(0, 200);
    if (typeof screenSummary === 'string') request.context.screen_summary = screenSummary.slice(0, 4000);
  }

  return request;
}

export default defineHandler({
  name: 'ai/command',
  methods: ['POST'],
  rateLimit: true,
  handler: async ({ req, body, logger, requestId, env }) => {
    const request = readRequest(body);
    const identity = await resolveIdentity(req, logger, { bodyDeviceId: request.device_id, env });

    const response = await interpretCommand({ request, identity, logger, requestId, env });
    return ok(response);
  },
});
