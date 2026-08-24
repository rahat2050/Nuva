/**
 * Shared request parsing for POST /api/ai/command and its SSE variant
 * POST /api/ai/command/stream. One parser → the two endpoints can never drift
 * apart on what they accept.
 */
import { NuvaError } from './errors.js';
import { MAX_COMMAND_CHARS } from './normalize.js';
import type { CommandRequest } from '../types/api.js';

export function readCommandRequest(body: Record<string, unknown>): CommandRequest {
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
