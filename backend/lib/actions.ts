/**
 * The action registry: runtime schema + metadata for each of the 15 registered
 * actions (§8).
 *
 * This is the whitelist. Anything the model produces that is not in here is
 * rejected and converted into a safe UNSUPPORTED response — the model can never
 * widen NUVA's capabilities by inventing an action type (§10, §26).
 *
 * Every object schema is `.strict()`: unknown keys are rejected rather than
 * silently forwarded to the Android executor.
 */
import { z } from 'zod';
import {
  ACTION_TYPES,
  MEDIA_APPS,
  MESSAGING_APPS,
  READ_SCREEN_SCOPES,
  RELATIVE_DAYS,
  SWIPE_DIRECTIONS,
  WEEKDAYS,
  type ActionType,
  type RiskLevel,
} from '../types/action';

/** Control characters are never legitimate in a voice transcript field. */
const CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;

export const safeText = (max: number, min = 1) =>
  z
    .string()
    .min(min)
    .max(max)
    .refine((value) => !CONTROL_CHARS.test(value), { message: 'control characters are not allowed' });

const appName = safeText(60).transform((value) => value.trim().toLowerCase());

const androidPackage = z
  .string()
  .max(255)
  .regex(/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/, 'not a valid Android package name');

const phoneNumber = z
  .string()
  .min(4)
  .max(24)
  .regex(/^\+?[0-9][0-9\s\-()]{3,23}$/, 'not a valid phone number');

/** 0..1 fraction of screen width/height — resolution independent. */
const fraction = z.number().min(0).max(1);

export const pointSchema = z.object({ x: fraction, y: fraction }).strict();

export const selectorSchema = z
  .object({
    resource_id: safeText(200).optional(),
    content_description: safeText(200).optional(),
    text: safeText(200).optional(),
    class_name: safeText(200).optional(),
    index: z.number().int().min(0).max(64).optional(),
  })
  .strict()
  .refine(
    (selector) =>
      Boolean(selector.resource_id ?? selector.content_description ?? selector.text ?? selector.class_name),
    { message: 'selector requires resource_id, content_description, text or class_name' },
  );

/**
 * URL guard: only http/https survive. This blocks `javascript:`, `data:`,
 * `file:` and Android `intent:` scheme abuse (§26 — no arbitrary execution).
 * Bare hosts like "youtube.com" are upgraded to https.
 */
export const safeUrl = z
  .string()
  .min(3)
  .max(2048)
  .transform((value, ctx) => {
    const candidate = /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(value) ? value : `https://${value}`;
    let parsed: URL;
    try {
      parsed = new URL(candidate);
    } catch {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'not a valid URL' });
      return z.NEVER;
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: `unsupported URL scheme: ${parsed.protocol}` });
      return z.NEVER;
    }
    if (parsed.hostname.length === 0) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'URL has no host' });
      return z.NEVER;
    }
    return parsed.toString();
  });

// --- Per-action schemas -----------------------------------------------------
// Kept as plain strict objects so they can form a discriminated union; the
// cross-field rules live in `crossFieldChecks` below.

const openApp = z.object({ type: z.literal('OPEN_APP'), app: appName, package: androidPackage.optional() }).strict();
const closeApp = z.object({ type: z.literal('CLOSE_APP'), app: appName, package: androidPackage.optional() }).strict();
const goHome = z.object({ type: z.literal('GO_HOME') }).strict();
const goBack = z.object({ type: z.literal('GO_BACK') }).strict();

const tap = z
  .object({
    type: z.literal('TAP'),
    target: selectorSchema.optional(),
    point: pointSchema.optional(),
    long_click: z.boolean().optional(),
  })
  .strict();

const typeText = z
  .object({
    type: z.literal('TYPE_TEXT'),
    text: safeText(1000),
    target: selectorSchema.optional(),
    submit: z.boolean().optional(),
  })
  .strict();

const swipe = z
  .object({
    type: z.literal('SWIPE'),
    direction: z.enum(SWIPE_DIRECTIONS).optional(),
    distance: z.enum(['short', 'medium', 'long']).optional(),
    from: pointSchema.optional(),
    to: pointSchema.optional(),
  })
  .strict();

const scroll = z
  .object({
    type: z.literal('SCROLL'),
    direction: z.enum(SWIPE_DIRECTIONS),
    amount: z.number().int().min(1).max(20).optional(),
    target: selectorSchema.optional(),
  })
  .strict();

const callContact = z
  .object({ type: z.literal('CALL_CONTACT'), contact: safeText(120), phone_number: phoneNumber.optional() })
  .strict();

const sendMessage = z
  .object({
    type: z.literal('SEND_MESSAGE'),
    app: z.enum(MESSAGING_APPS),
    contact: safeText(120),
    message: safeText(2000),
    phone_number: phoneNumber.optional(),
  })
  .strict();

const setAlarm = z
  .object({
    type: z.literal('SET_ALARM'),
    hour: z.number().int().min(0).max(23),
    minute: z.number().int().min(0).max(59),
    label: safeText(120).optional(),
    relative_day: z.enum(RELATIVE_DAYS).optional(),
    days: z.array(z.enum(WEEKDAYS)).min(1).max(7).optional(),
  })
  .strict();

const setTimer = z
  .object({
    type: z.literal('SET_TIMER'),
    duration_seconds: z.number().int().min(1).max(86_400),
    label: safeText(120).optional(),
  })
  .strict();

const openUrl = z.object({ type: z.literal('OPEN_URL'), url: safeUrl }).strict();

const playMedia = z
  .object({ type: z.literal('PLAY_MEDIA'), query: safeText(300), app: z.enum(MEDIA_APPS).optional() })
  .strict();

const readScreen = z.object({ type: z.literal('READ_SCREEN'), scope: z.enum(READ_SCREEN_SCOPES).optional() }).strict();

export const ACTION_SCHEMAS = {
  OPEN_APP: openApp,
  CLOSE_APP: closeApp,
  GO_HOME: goHome,
  GO_BACK: goBack,
  TAP: tap,
  TYPE_TEXT: typeText,
  SWIPE: swipe,
  SCROLL: scroll,
  CALL_CONTACT: callContact,
  SEND_MESSAGE: sendMessage,
  SET_ALARM: setAlarm,
  SET_TIMER: setTimer,
  OPEN_URL: openUrl,
  PLAY_MEDIA: playMedia,
  READ_SCREEN: readScreen,
} as const satisfies Record<ActionType, z.ZodTypeAny>;

/** Rules that span several fields of one action. */
function crossFieldChecks(action: z.infer<typeof baseUnion>, ctx: z.RefinementCtx): void {
  switch (action.type) {
    case 'TAP':
      if (!action.target && !action.point) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'TAP requires a semantic target or a point fallback',
          path: ['target'],
        });
      }
      break;
    case 'SWIPE':
      if (!action.direction && !(action.from && action.to)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'SWIPE requires a direction, or both from and to points',
          path: ['direction'],
        });
      }
      break;
    default:
      break;
  }
}

const baseUnion = z.discriminatedUnion('type', [
  openApp,
  closeApp,
  goHome,
  goBack,
  tap,
  typeText,
  swipe,
  scroll,
  callContact,
  sendMessage,
  setAlarm,
  setTimer,
  openUrl,
  playMedia,
  readScreen,
]);

/** Validates any executable NUVA action. */
export const actionSchema = baseUnion.superRefine(crossFieldChecks);

export type ParsedAction = z.infer<typeof actionSchema>;

// --- Registry metadata ------------------------------------------------------

export interface ActionMeta {
  /** Baseline risk before escalation rules run (§11). */
  baseRisk: RiskLevel;
  /** Human-readable catalogue entry, reused in the AI system prompt. */
  signature: string;
  description: string;
}

export const ACTION_META: Record<ActionType, ActionMeta> = {
  OPEN_APP: { baseRisk: 'low', signature: '{ "type": "OPEN_APP", "app": "<name>" }', description: 'Launch an app.' },
  CLOSE_APP: { baseRisk: 'low', signature: '{ "type": "CLOSE_APP", "app": "<name>" }', description: 'Close/stop an app.' },
  GO_HOME: { baseRisk: 'low', signature: '{ "type": "GO_HOME" }', description: 'Go to the home screen.' },
  GO_BACK: { baseRisk: 'low', signature: '{ "type": "GO_BACK" }', description: 'Press the system back button.' },
  TAP: {
    baseRisk: 'low',
    signature: '{ "type": "TAP", "target": { "resource_id"|"content_description"|"text"|"class_name": "<v>" }, "long_click"?: bool }',
    description: 'Tap a UI element. Prefer a semantic target; "point": {x,y} (0..1 fractions) is a last resort.',
  },
  TYPE_TEXT: {
    baseRisk: 'low',
    signature: '{ "type": "TYPE_TEXT", "text": "<text>", "target"?: <selector>, "submit"?: bool }',
    description: 'Type text into the focused or targeted field.',
  },
  SWIPE: {
    baseRisk: 'low',
    signature: '{ "type": "SWIPE", "direction": "up"|"down"|"left"|"right", "distance"?: "short"|"medium"|"long" }',
    description: 'Swipe across the screen.',
  },
  SCROLL: {
    baseRisk: 'low',
    signature: '{ "type": "SCROLL", "direction": "up"|"down"|"left"|"right", "amount"?: 1-20 }',
    description: 'Scroll the scrollable container.',
  },
  CALL_CONTACT: {
    baseRisk: 'medium',
    signature: '{ "type": "CALL_CONTACT", "contact": "<name>", "phone_number"?: "<number>" }',
    description: 'Place a phone call. Always needs confirmation.',
  },
  SEND_MESSAGE: {
    baseRisk: 'medium',
    signature:
      '{ "type": "SEND_MESSAGE", "app": "whatsapp"|"sms"|"telegram"|"messenger"|"signal"|"viber"|"imo", "contact": "<name>", "message": "<text>" }',
    description: 'Send a message. Always needs confirmation.',
  },
  SET_ALARM: {
    baseRisk: 'low',
    signature: '{ "type": "SET_ALARM", "hour": 0-23, "minute": 0-59, "relative_day"?: "today"|"tomorrow", "label"?: "<text>" }',
    description: 'Set an alarm using a 24-hour clock.',
  },
  SET_TIMER: {
    baseRisk: 'low',
    signature: '{ "type": "SET_TIMER", "duration_seconds": 1-86400, "label"?: "<text>" }',
    description: 'Start a countdown timer.',
  },
  OPEN_URL: {
    baseRisk: 'low',
    signature: '{ "type": "OPEN_URL", "url": "https://…" }',
    description: 'Open a web page. Use this for web searches, e.g. https://www.google.com/search?q=…',
  },
  PLAY_MEDIA: {
    baseRisk: 'low',
    signature: '{ "type": "PLAY_MEDIA", "query": "<what to play>", "app"?: "youtube"|"spotify"|"local" }',
    description: 'Search and play media.',
  },
  READ_SCREEN: {
    baseRisk: 'low',
    signature: '{ "type": "READ_SCREEN", "scope"?: "visible"|"focused"|"all" }',
    description: 'Read the current screen aloud.',
  },
};

export function isRegisteredActionType(value: unknown): value is ActionType {
  return typeof value === 'string' && (ACTION_TYPES as readonly string[]).includes(value);
}

/** Catalogue text injected into the AI system prompt, generated from the registry
 *  so the prompt can never drift from the validator. */
export function actionCatalogue(): string {
  return ACTION_TYPES.map((type) => {
    const meta = ACTION_META[type];
    return `- ${type} — ${meta.description}\n  ${meta.signature}`;
  }).join('\n');
}
