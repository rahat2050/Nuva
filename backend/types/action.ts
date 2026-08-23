/**
 * NUVA action contract — the single source of truth shared by the backend and
 * (from PHRASE 2) the Android client.
 *
 * Master prompt §7/§8: the AI never controls Android. It only emits one of the
 * registered actions below, which Android validates again before executing.
 *
 * This module is intentionally dependency-free (no zod, no runtime logic) so it
 * can be mirrored 1:1 into Kotlin later.
 */

/** The 15 actions of the initial registry (§8). Nothing else may be executed. */
export const ACTION_TYPES = [
  'OPEN_APP',
  'CLOSE_APP',
  'GO_HOME',
  'GO_BACK',
  'TAP',
  'TYPE_TEXT',
  'SWIPE',
  'SCROLL',
  'CALL_CONTACT',
  'SEND_MESSAGE',
  'SET_ALARM',
  'SET_TIMER',
  'OPEN_URL',
  'PLAY_MEDIA',
  'READ_SCREEN',
] as const;

export type ActionType = (typeof ACTION_TYPES)[number];

/**
 * Sentinel intent for "the request is not in the registry" (§8: return a safe
 * unsupported-action response). UNSUPPORTED is NOT an action — it carries a
 * `null` action and must never reach the executor.
 */
export const UNSUPPORTED_INTENT = 'UNSUPPORTED' as const;

export type IntentName = ActionType | typeof UNSUPPORTED_INTENT;

/** Risk tiers (§11). */
export const RISK_LEVELS = ['low', 'medium', 'high'] as const;
export type RiskLevel = (typeof RISK_LEVELS)[number];

/** Languages NUVA understands (§15). `banglish` = Bangla written in Latin script. */
export const LANGUAGES = ['bn', 'en', 'banglish'] as const;
export type Language = (typeof LANGUAGES)[number];

/** Lifecycle of a command, mirrored by the `commands.status` DB constraint. */
export const COMMAND_STATUSES = [
  'ready',
  'pending_confirmation',
  'confirmed',
  'rejected',
  'executing',
  'completed',
  'failed',
  'unsupported',
] as const;
export type CommandStatus = (typeof COMMAND_STATUSES)[number];

export const SWIPE_DIRECTIONS = ['up', 'down', 'left', 'right'] as const;
export type SwipeDirection = (typeof SWIPE_DIRECTIONS)[number];

export const MESSAGING_APPS = ['whatsapp', 'sms', 'telegram', 'messenger', 'signal', 'viber', 'imo'] as const;
export type MessagingApp = (typeof MESSAGING_APPS)[number];

export const MEDIA_APPS = ['youtube', 'spotify', 'local', 'unknown'] as const;
export type MediaApp = (typeof MEDIA_APPS)[number];

export const READ_SCREEN_SCOPES = ['visible', 'focused', 'all'] as const;
export type ReadScreenScope = (typeof READ_SCREEN_SCOPES)[number];

export const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
export type Weekday = (typeof WEEKDAYS)[number];

export const RELATIVE_DAYS = ['today', 'tomorrow'] as const;
export type RelativeDay = (typeof RELATIVE_DAYS)[number];

/**
 * Semantic UI selector. Accessibility automation must prefer these fields in the
 * documented priority order (§9): resource-id → contentDescription → text →
 * class name → coordinate fallback.
 */
export interface UiSelector {
  resource_id?: string;
  content_description?: string;
  text?: string;
  class_name?: string;
  /** Which match to use when a selector matches several nodes. */
  index?: number;
}

/** Screen point expressed as a 0..1 fraction of screen size (device independent). */
export interface ScreenPoint {
  x: number;
  y: number;
}

export interface OpenAppAction {
  type: 'OPEN_APP';
  app: string;
  /** Optional Android package hint resolved server-side, e.g. com.whatsapp. */
  package?: string;
}

export interface CloseAppAction {
  type: 'CLOSE_APP';
  app: string;
  package?: string;
}

export interface GoHomeAction {
  type: 'GO_HOME';
}

export interface GoBackAction {
  type: 'GO_BACK';
}

export interface TapAction {
  type: 'TAP';
  target?: UiSelector;
  point?: ScreenPoint;
  long_click?: boolean;
}

export interface TypeTextAction {
  type: 'TYPE_TEXT';
  text: string;
  target?: UiSelector;
  /** Press enter / send after typing. */
  submit?: boolean;
}

export interface SwipeAction {
  type: 'SWIPE';
  direction?: SwipeDirection;
  distance?: 'short' | 'medium' | 'long';
  from?: ScreenPoint;
  to?: ScreenPoint;
}

export interface ScrollAction {
  type: 'SCROLL';
  direction: SwipeDirection;
  amount?: number;
  target?: UiSelector;
}

export interface CallContactAction {
  type: 'CALL_CONTACT';
  contact: string;
  phone_number?: string;
}

export interface SendMessageAction {
  type: 'SEND_MESSAGE';
  app: MessagingApp;
  contact: string;
  message: string;
  phone_number?: string;
}

export interface SetAlarmAction {
  type: 'SET_ALARM';
  hour: number;
  minute: number;
  label?: string;
  relative_day?: RelativeDay;
  days?: Weekday[];
}

export interface SetTimerAction {
  type: 'SET_TIMER';
  duration_seconds: number;
  label?: string;
}

export interface OpenUrlAction {
  type: 'OPEN_URL';
  url: string;
}

export interface PlayMediaAction {
  type: 'PLAY_MEDIA';
  query: string;
  app?: MediaApp;
}

export interface ReadScreenAction {
  type: 'READ_SCREEN';
  scope?: ReadScreenScope;
}

/** Discriminated union of every executable action. */
export type NuvaAction =
  | OpenAppAction
  | CloseAppAction
  | GoHomeAction
  | GoBackAction
  | TapAction
  | TypeTextAction
  | SwipeAction
  | ScrollAction
  | CallContactAction
  | SendMessageAction
  | SetAlarmAction
  | SetTimerAction
  | OpenUrlAction
  | PlayMediaAction
  | ReadScreenAction;

/**
 * The validated decision NUVA hands to the Android command executor.
 * `risk` and `requires_confirmation` are always recomputed server-side and are
 * never trusted from the model output (§11, §26).
 */
export interface ActionDecision {
  intent: IntentName;
  action: NuvaAction | null;
  risk: RiskLevel;
  requires_confirmation: boolean;
  /** Model self-reported confidence, 0..1, when available. */
  confidence?: number;
  /** Short sentence for TTS, in the user's language. */
  speech: string;
  /** Present when intent is UNSUPPORTED, or when risk was escalated. */
  reasons?: string[];
}
