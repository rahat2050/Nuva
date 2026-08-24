/**
 * /api/devices — Android device registration (roadmap follow-up; the table
 * already existed in supabase/migrations/0001_init.sql).
 *
 * GET  /api/devices  → the signed-in user's registered devices
 * POST /api/devices  { device_name, android_version? } → register/refresh
 *
 * The app calls POST once per install (and after OS upgrades) so the account
 * knows which devices are linked — needed later for targeting and audit.
 */

export const config = {
  maxDuration: 10,
};

import { defineHandler, ok } from '../../lib/http.js';
import { requireUser, resolveIdentity } from '../../lib/auth.js';
import { listDevices, registerDevice } from '../../lib/repository.js';
import { NuvaError } from '../../lib/errors.js';

const MAX_DEVICE_NAME = 120;
const MAX_ANDROID_VERSION = 40;
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

function readDeviceName(value: unknown): string {
  if (typeof value !== 'string') {
    throw new NuvaError('BAD_REQUEST', '`device_name` is required and must be a string');
  }
  const name = value.trim();
  if (name.length === 0 || name.length > MAX_DEVICE_NAME) {
    throw new NuvaError('BAD_REQUEST', `\`device_name\` must be 1-${MAX_DEVICE_NAME} characters`);
  }
  if (CONTROL_CHARS.test(name)) {
    throw new NuvaError('BAD_REQUEST', '`device_name` must not contain control characters');
  }
  return name;
}

function readAndroidVersion(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') {
    throw new NuvaError('BAD_REQUEST', '`android_version` must be a string');
  }
  const version = value.trim();
  if (version.length === 0) return undefined;
  if (version.length > MAX_ANDROID_VERSION) {
    throw new NuvaError('BAD_REQUEST', `\`android_version\` must be at most ${MAX_ANDROID_VERSION} characters`);
  }
  if (CONTROL_CHARS.test(version)) {
    throw new NuvaError('BAD_REQUEST', '`android_version` must not contain control characters');
  }
  return version;
}

export default defineHandler({
  name: 'devices',
  methods: ['GET', 'POST'],
  rateLimit: true,
  handler: async ({ req, method, body, logger, env }) => {
    const identity = await resolveIdentity(req, logger, { env });
    const userId = requireUser(identity);

    if (method === 'GET') {
      const devices = await listDevices({ userId }, env);
      return ok({ ok: true, count: devices.length, devices });
    }

    const deviceName = readDeviceName(body['device_name']);
    const androidVersion = readAndroidVersion(body['android_version']);

    const device = await registerDevice(
      { userId, deviceName, ...(androidVersion !== undefined ? { androidVersion } : {}) },
      env,
    );
    logger.info('device registered', { device_id: device.id });
    return { status: 201, body: { ok: true, device } };
  },
});
