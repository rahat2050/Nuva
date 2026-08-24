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
  maxDuration: 10,
};

import { defineHandler, ok } from '../../lib/http.js';
import { resolveIdentity } from '../../lib/auth.js';
import { interpretCommand } from '../../lib/pipeline.js';
import { readCommandRequest } from '../../lib/commandRequest.js';

export default defineHandler({
  name: 'ai/command',
  methods: ['POST'],
  rateLimit: true,
  handler: async ({ req, body, logger, requestId, env }) => {
    const request = readCommandRequest(body);
    const identity = await resolveIdentity(req, logger, { bodyDeviceId: request.device_id, env });

    const response = await interpretCommand({ request, identity, logger, requestId, env });
    return ok(response);
  },
});
