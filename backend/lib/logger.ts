/**
 * Structured JSON logger with secret redaction.
 *
 * §22 asks for useful logging; §12 forbids leaking secrets. Every value logged
 * passes through `redact()`, which blanks secret-looking keys and truncates long
 * strings so a transcript never floods the log.
 *
 * HARDENED: getEnv() is called defensively — if it throws, we fall back to
 * info level instead of crashing the function (which would become
 * FUNCTION_INVOCATION_FAILED on Vercel).
 */
import { getEnv, type LogLevel } from './env.js';

const LEVEL_WEIGHT: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40 };

const SECRET_KEY_PATTERN = /(api[_-]?key|secret|token|password|passwd|authorization|bearer|service[_-]?role|jwt|cookie)/i;
const MAX_STRING = 500;

export type LogFields = Record<string, unknown>;

function redactValue(value: unknown, depth: number): unknown {
  if (value === null || value === undefined) return value;
  if (typeof value === 'string') {
    return value.length > MAX_STRING ? `${value.slice(0, MAX_STRING)}…[${value.length} chars]` : value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') return value;
  if (value instanceof Error) {
    return { name: value.name, message: redactValue(value.message, depth) };
  }
  if (depth >= 4) return '[truncated]';
  if (Array.isArray(value)) {
    return value.slice(0, 20).map((item) => redactValue(item, depth + 1));
  }
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [key, val] of Object.entries(value as Record<string, unknown>)) {
      out[key] = SECRET_KEY_PATTERN.test(key) ? '[redacted]' : redactValue(val, depth + 1);
    }
    return out;
  }
  return '[unloggable]';
}

export function redact(fields: LogFields): LogFields {
  return redactValue(fields, 0) as LogFields;
}

export interface Logger {
  debug(message: string, fields?: LogFields): void;
  info(message: string, fields?: LogFields): void;
  warn(message: string, fields?: LogFields): void;
  error(message: string, fields?: LogFields): void;
  child(fields: LogFields): Logger;
}

function getLogLevelSafe(): LogLevel {
  try {
    return getEnv().logLevel;
  } catch {
    return 'info';
  }
}

function emit(level: LogLevel, message: string, base: LogFields, fields?: LogFields): void {
  try {
    const currentLevel = getLogLevelSafe();
    const min = LEVEL_WEIGHT[currentLevel] ?? LEVEL_WEIGHT['info'] ?? 20;
    const lvlWeight = LEVEL_WEIGHT[level] ?? 20;
    if (lvlWeight < min) return;

    const payload = {
      level,
      time: new Date().toISOString(),
      service: 'nuva-backend',
      message,
      ...redact({ ...base, ...(fields ?? {}) }),
    };

    const line = JSON.stringify(payload);
    if (level === 'error') console.error(line);
    else if (level === 'warn') console.warn(line);
    else console.log(line);
  } catch {
    // Logger must never throw — if JSON.stringify fails or anything else,
    // fall back to a plain console.log so we don't crash the function.
    try {
      console.log(`[${level}] ${message}`);
    } catch {
      // ultimate fallback: do nothing
    }
  }
}

export function createLogger(base: LogFields = {}): Logger {
  return {
    debug: (message, fields) => emit('debug', message, base, fields),
    info: (message, fields) => emit('info', message, base, fields),
    warn: (message, fields) => emit('warn', message, base, fields),
    error: (message, fields) => emit('error', message, base, fields),
    child: (fields) => createLogger({ ...base, ...fields }),
  };
}
