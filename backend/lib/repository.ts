/**
 * Supabase persistence (§13, §17).
 *
 * Two classes of operation:
 *   * `record*` — telemetry-style writes (history, memory sync). They must never
 *     break a working command, so failures are logged and swallowed.
 *   * `list*` / `upsert*` / `delete*` — user-visible operations behind explicit
 *     endpoints. These throw so the caller learns the truth.
 *
 * Every write carries a user_id that came from a verified JWT (see auth.ts).
 */
import { getServiceClient } from './supabase.js';
import { getEnv, type NuvaEnv } from './env.js';
import { NuvaError } from './errors.js';
import type { Logger } from './logger.js';
import type { CommandStatus, RiskLevel } from '../types/action.js';

export interface CommandRow {
  id: string;
  command: string;
  intent: string;
  action: unknown;
  risk: RiskLevel;
  status: CommandStatus;
  error: string | null;
  created_at: string;
}

export interface MemoryRow {
  key: string;
  value: string;
  created_at: string;
  updated_at: string;
}

export function persistenceEnabled(env: NuvaEnv = getEnv()): boolean {
  return env.persistEnabled;
}

/** Fire-and-forget conversation logging. Never throws. */
export async function recordConversation(
  params: { userId: string; role: 'user' | 'assistant' | 'system'; message: string },
  logger: Logger,
  env: NuvaEnv = getEnv(),
): Promise<void> {
  if (!persistenceEnabled(env)) return;
  try {
    const { error } = await getServiceClient(env).from('conversations').insert({
      user_id: params.userId,
      role: params.role,
      message: params.message.slice(0, 8000),
    });
    if (error) logger.warn('failed to record conversation', { code: error.code, message: error.message });
  } catch (err) {
    logger.warn('failed to record conversation', { error: err instanceof Error ? err.message : 'unknown' });
  }
}

/** Fire-and-forget command logging. Returns the row id when available. */
export async function recordCommand(
  params: {
    userId: string;
    command: string;
    intent: string;
    action: unknown;
    risk: RiskLevel;
    status: CommandStatus;
    error?: string;
  },
  logger: Logger,
  env: NuvaEnv = getEnv(),
): Promise<string | null> {
  if (!persistenceEnabled(env)) return null;
  try {
    const { data, error } = await getServiceClient(env)
      .from('commands')
      .insert({
        user_id: params.userId,
        command: params.command.slice(0, 2000),
        intent: params.intent,
        action: params.action ?? null,
        risk: params.risk,
        status: params.status,
        error: params.error?.slice(0, 1000) ?? null,
      })
      .select('id')
      .single();

    if (error) {
      logger.warn('failed to record command', { code: error.code, message: error.message });
      return null;
    }
    return (data as { id: string }).id;
  } catch (err) {
    logger.warn('failed to record command', { error: err instanceof Error ? err.message : 'unknown' });
    return null;
  }
}

/** Explicit status update from the Android executor. Throws on failure. */
export async function updateCommandStatus(
  params: { userId: string; commandId: string; status: CommandStatus; error?: string },
  env: NuvaEnv = getEnv(),
): Promise<CommandRow> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  const { data, error } = await getServiceClient(env)
    .from('commands')
    .update({ status: params.status, error: params.error?.slice(0, 1000) ?? null, updated_at: new Date().toISOString() })
    .eq('id', params.commandId)
    .eq('user_id', params.userId)
    .select('id, command, intent, action, risk, status, error, created_at')
    .maybeSingle();

  if (error) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not update command: ${error.message}`, {
      details: { code: error.code },
    });
  }
  if (!data) {
    throw new NuvaError('NOT_FOUND', 'No command with that id belongs to this user', { expected: true });
  }
  return data as CommandRow;
}

export async function listCommands(
  params: { userId: string; limit: number },
  env: NuvaEnv = getEnv(),
): Promise<CommandRow[]> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  const { data, error } = await getServiceClient(env)
    .from('commands')
    .select('id, command, intent, action, risk, status, error, created_at')
    .eq('user_id', params.userId)
    .order('created_at', { ascending: false })
    .limit(params.limit);

  if (error) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not read command history: ${error.message}`, {
      details: { code: error.code },
    });
  }
  return (data ?? []) as CommandRow[];
}

export async function listMemories(
  params: { userId: string; key?: string },
  env: NuvaEnv = getEnv(),
): Promise<MemoryRow[]> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  let query = getServiceClient(env)
    .from('memories')
    .select('key, value, created_at, updated_at')
    .eq('user_id', params.userId);

  if (params.key) query = query.eq('key', params.key);

  const { data, error } = await query.order('updated_at', { ascending: false }).limit(200);
  if (error) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not read memories: ${error.message}`, {
      details: { code: error.code },
    });
  }
  return (data ?? []) as MemoryRow[];
}

export async function upsertMemory(
  params: { userId: string; key: string; value: string },
  env: NuvaEnv = getEnv(),
): Promise<MemoryRow> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  const { data, error } = await getServiceClient(env)
    .from('memories')
    .upsert(
      { user_id: params.userId, key: params.key, value: params.value, updated_at: new Date().toISOString() },
      { onConflict: 'user_id,key' },
    )
    .select('key, value, created_at, updated_at')
    .single();

  if (error) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not save memory: ${error.message}`, {
      details: { code: error.code },
    });
  }
  return data as MemoryRow;
}

export async function deleteMemory(
  params: { userId: string; key: string },
  env: NuvaEnv = getEnv(),
): Promise<boolean> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  const { data, error } = await getServiceClient(env)
    .from('memories')
    .delete()
    .eq('user_id', params.userId)
    .eq('key', params.key)
    .select('key');

  if (error) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not delete memory: ${error.message}`, {
      details: { code: error.code },
    });
  }
  return (data ?? []).length > 0;
}

export interface DeviceRow {
  id: string;
  device_name: string;
  android_version: string | null;
  created_at: string;
}

const DEVICE_COLUMNS = 'id, device_name, android_version, created_at';

/**
 * Registers (or refreshes) a device for a user — roadmap follow-up
 * "POST /api/devices". Idempotent per (user, device_name): re-registering the
 * same device updates android_version instead of growing duplicate rows.
 */
export async function registerDevice(
  params: { userId: string; deviceName: string; androidVersion?: string },
  env: NuvaEnv = getEnv(),
): Promise<DeviceRow> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  const service = getServiceClient(env);

  const { data: existing, error: selectError } = await service
    .from('devices')
    .select(DEVICE_COLUMNS)
    .eq('user_id', params.userId)
    .eq('device_name', params.deviceName)
    .maybeSingle();

  if (selectError) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not look up devices: ${selectError.message}`, {
      details: { code: selectError.code },
    });
  }

  if (existing) {
    if (params.androidVersion !== undefined && params.androidVersion !== existing.android_version) {
      const { data: updated, error: updateError } = await service
        .from('devices')
        .update({ android_version: params.androidVersion })
        .eq('id', (existing as DeviceRow).id)
        .select(DEVICE_COLUMNS)
        .single();
      if (updateError) {
        throw new NuvaError('PERSISTENCE_FAILED', `Could not update device: ${updateError.message}`, {
          details: { code: updateError.code },
        });
      }
      return updated as DeviceRow;
    }
    return existing as DeviceRow;
  }

  const { data: inserted, error: insertError } = await service
    .from('devices')
    .insert({
      user_id: params.userId,
      device_name: params.deviceName,
      android_version: params.androidVersion ?? null,
    })
    .select(DEVICE_COLUMNS)
    .single();

  if (insertError) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not register device: ${insertError.message}`, {
      details: { code: insertError.code },
    });
  }
  return inserted as DeviceRow;
}

export async function listDevices(params: { userId: string }, env: NuvaEnv = getEnv()): Promise<DeviceRow[]> {
  if (!persistenceEnabled(env)) {
    throw new NuvaError('NOT_CONFIGURED', 'Persistence is disabled (SUPABASE_SERVICE_ROLE_KEY missing)');
  }

  const { data, error } = await getServiceClient(env)
    .from('devices')
    .select(DEVICE_COLUMNS)
    .eq('user_id', params.userId)
    .order('created_at', { ascending: false })
    .limit(50);

  if (error) {
    throw new NuvaError('PERSISTENCE_FAILED', `Could not read devices: ${error.message}`, {
      details: { code: error.code },
    });
  }
  return (data ?? []) as DeviceRow[];
}
