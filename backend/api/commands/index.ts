/**
 * /api/commands — command history and execution reporting.
 *
 * GET  /api/commands?limit=50   → the signed-in user's recent commands
 * POST /api/commands            → report the outcome of an execution
 *
 * The Android CommandExecutor calls POST after it finishes (or fails, or the
 * user rejects a confirmation) so the audit trail reflects what really happened
 * on the device, not just what was planned.
 *
 * Both require a verified Supabase user: history is inherently per-user.
 */
import { defineHandler, ok } from '../../lib/http';
import { requireUser, resolveIdentity } from '../../lib/auth';
import { listCommands, recordCommand, updateCommandStatus } from '../../lib/repository';
import { NuvaError } from '../../lib/errors';
import { COMMAND_STATUSES, RISK_LEVELS, type CommandStatus, type RiskLevel } from '../../types/action';
import { isRegisteredActionType } from '../../lib/actions';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function readStatus(value: unknown): CommandStatus {
  if (typeof value !== 'string' || !(COMMAND_STATUSES as readonly string[]).includes(value)) {
    throw new NuvaError('BAD_REQUEST', `\`status\` must be one of: ${COMMAND_STATUSES.join(', ')}`);
  }
  return value as CommandStatus;
}

function readRisk(value: unknown): RiskLevel {
  if (typeof value !== 'string' || !(RISK_LEVELS as readonly string[]).includes(value)) return 'low';
  return value as RiskLevel;
}

export default defineHandler({
  name: 'commands',
  methods: ['GET', 'POST'],
  rateLimit: true,
  handler: async ({ req, method, body, query, logger, env }) => {
    const identity = await resolveIdentity(req, logger, { env });
    const userId = requireUser(identity);

    if (method === 'GET') {
      const requested = Number.parseInt(query['limit'] ?? '50', 10);
      const limit = Number.isFinite(requested) ? Math.min(200, Math.max(1, requested)) : 50;
      const commands = await listCommands({ userId, limit }, env);
      return ok({ ok: true, count: commands.length, commands });
    }

    const status = readStatus(body['status']);
    const error = typeof body['error'] === 'string' ? body['error'].slice(0, 1000) : undefined;
    const commandId = body['command_id'];

    // Update an existing row created by /api/ai/command.
    if (typeof commandId === 'string') {
      if (!UUID_PATTERN.test(commandId)) {
        throw new NuvaError('BAD_REQUEST', '`command_id` must be a UUID');
      }
      const updated = await updateCommandStatus(
        { userId, commandId, status, ...(error !== undefined ? { error } : {}) },
        env,
      );
      logger.info('command status updated', { command_id: updated.id, status });
      return ok({ ok: true, command: updated });
    }

    // Otherwise create a standalone audit row (e.g. an action executed offline).
    const command = body['command'];
    if (typeof command !== 'string' || command.trim().length === 0) {
      throw new NuvaError('BAD_REQUEST', '`command` is required when `command_id` is omitted');
    }
    const intent = body['intent'];
    if (typeof intent !== 'string' || (!isRegisteredActionType(intent) && intent !== 'UNSUPPORTED')) {
      throw new NuvaError('BAD_REQUEST', '`intent` must be a registered action type or UNSUPPORTED');
    }

    const id = await recordCommand(
      {
        userId,
        command,
        intent,
        action: body['action'] ?? null,
        risk: readRisk(body['risk']),
        status,
        ...(error !== undefined ? { error } : {}),
      },
      logger,
      env,
    );

    if (id === null) {
      throw new NuvaError('PERSISTENCE_FAILED', 'Could not record the command');
    }
    return { status: 201, body: { ok: true, command_id: id } };
  },
});
