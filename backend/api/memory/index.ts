/**
 * /api/memory — NUVA's long-term, non-sensitive user memory (§17).
 *
 * GET    /api/memory[?key=preferred_language]  → list (or fetch one)
 * POST   /api/memory  { key, value }           → upsert
 * DELETE /api/memory?key=…                     → forget
 *
 * §17/§12: memory is for preferences (language, assistant name, favourite apps),
 * not for secrets. Keys that look like credentials are rejected outright so a
 * misbehaving client cannot turn NUVA's memory into a password store.
 */

export const config = {
  maxDuration: 10,
};

import { defineHandler, ok } from '../../lib/http';
import { requireUser, resolveIdentity } from '../../lib/auth';
import { deleteMemory, listMemories, upsertMemory } from '../../lib/repository';
import { NuvaError } from '../../lib/errors';

const KEY_PATTERN = /^[a-z0-9][a-z0-9_.-]{0,119}$/;
const MAX_VALUE_CHARS = 4000;
const FORBIDDEN_KEY = /(password|passwd|secret|token|api[_-]?key|otp|pin|cvv|credit[_-]?card|private[_-]?key|seed[_-]?phrase)/i;

function readKey(value: unknown): string {
  if (typeof value !== 'string') {
    throw new NuvaError('BAD_REQUEST', '`key` is required and must be a string');
  }
  const key = value.trim().toLowerCase();
  if (!KEY_PATTERN.test(key)) {
    throw new NuvaError(
      'BAD_REQUEST',
      '`key` must be 1-120 chars of lowercase letters, digits, dot, dash or underscore',
    );
  }
  if (FORBIDDEN_KEY.test(key)) {
    throw new NuvaError('BAD_REQUEST', 'NUVA does not store credentials in memory');
  }
  return key;
}

export default defineHandler({
  name: 'memory',
  methods: ['GET', 'POST', 'DELETE'],
  rateLimit: true,
  handler: async ({ req, method, body, query, logger, env }) => {
    const identity = await resolveIdentity(req, logger, { env });
    const userId = requireUser(identity);

    if (method === 'GET') {
      const rawKey = query['key'];
      const memories = await listMemories(
        { userId, ...(rawKey !== undefined ? { key: readKey(rawKey) } : {}) },
        env,
      );
      return ok({ ok: true, count: memories.length, memories });
    }

    if (method === 'DELETE') {
      const key = readKey(query['key']);
      const deleted = await deleteMemory({ userId, key }, env);
      if (!deleted) {
        throw new NuvaError('NOT_FOUND', `No memory stored under \`${key}\``, { expected: true });
      }
      logger.info('memory deleted', { key });
      return ok({ ok: true, key, deleted: true });
    }

    const key = readKey(body['key']);
    const value = body['value'];
    if (typeof value !== 'string' || value.trim().length === 0) {
      throw new NuvaError('BAD_REQUEST', '`value` is required and must be a non-empty string');
    }
    if (value.length > MAX_VALUE_CHARS) {
      throw new NuvaError('PAYLOAD_TOO_LARGE', `\`value\` must be at most ${MAX_VALUE_CHARS} characters`);
    }

    const memory = await upsertMemory({ userId, key, value }, env);
    logger.info('memory saved', { key });
    return ok({ ok: true, memory });
  },
});
