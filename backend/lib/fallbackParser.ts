/**
 * Deterministic offline parser — a safety net, NOT a second brain.
 *
 * Why it exists:
 *   * §24 requires graceful degradation when the AI is unreachable.
 *   * It lets the whole pipeline be tested end to end without a Groq key, which
 *     keeps PHRASE 1 verifiable in CI and in this sandbox.
 *
 * Hard limits that keep it safe:
 *   * It can ONLY produce low-risk, reversible actions. It never produces
 *     SEND_MESSAGE, CALL_CONTACT, TAP, TYPE_TEXT or SWIPE — anything that could
 *     act on someone else's behalf stays with the AI + confirmation flow.
 *   * It returns null whenever it is not confident, which surfaces the normal
 *     "I couldn't understand that command" path.
 *   * Its output still goes through validateAction() and assessRisk().
 */
import { findAppInText } from './apps.js';
import type { ParsedAction } from './actions.js';

/** Regexes are matched against lowercased, wake-word-stripped text. */
interface Rule {
  name: string;
  run: (text: string) => ParsedAction | null;
}

const OPEN_VERBS = /(open|launch|start|khol|kholo|khulo|kholen|chalu|খোল|খুলো|চালু|ওপেন)/;
const CLOSE_VERBS = /(close|quit|exit|bondho|bondo|bandho|বন্ধ|ক্লোজ)/;

function hasWord(text: string, pattern: RegExp): boolean {
  return pattern.test(text);
}

/** "10 minute", "5 min", "30 second", "2 ghonta", "১০ মিনিট" → seconds */
function parseDuration(text: string): number | null {
  const match = /(\d{1,4})\s*(seconds?|secs?|sec|second|minutes?|mins?|min|minit|ghonta|hours?|hrs?|ঘণ্টা|ঘন্টা|মিনিট|সেকেন্ড)/.exec(text);
  if (!match) return null;
  const amount = Number.parseInt(match[1] ?? '', 10);
  const unit = match[2] ?? '';
  if (!Number.isFinite(amount) || amount <= 0) return null;

  if (/^(seconds?|secs?|sec|second|সেকেন্ড)$/.test(unit)) return Math.min(amount, 86_400);
  if (/^(ghonta|hours?|hrs?|ঘণ্টা|ঘন্টা)$/.test(unit)) return Math.min(amount * 3600, 86_400);
  return Math.min(amount * 60, 86_400);
}

/** "kal shokal 7 tay" → { hour: 7, minute: 0, tomorrow: true } */
function parseClockTime(text: string): { hour: number; minute: number; tomorrow: boolean } | null {
  const timeMatch = /(\d{1,2})\s*[:.]\s*(\d{2})/.exec(text) ?? /\b(\d{1,2})\s*(?:ta|tay|টা|টায়|o'?clock)?\b/.exec(text);
  if (!timeMatch) return null;

  let hour = Number.parseInt(timeMatch[1] ?? '', 10);
  const minute = timeMatch[2] ? Number.parseInt(timeMatch[2], 10) : 0;
  if (!Number.isFinite(hour) || hour < 0 || hour > 23) return null;
  if (!Number.isFinite(minute) || minute < 0 || minute > 59) return null;

  const morning = /(shokal|sokal|sokale|morning|am|ভোর|সকাল)/.test(text);
  const afternoon = /(dupur|noon|দুপুর)/.test(text);
  const evening = /(bikal|bikel|bikale|evening|pm|বিকাল|বিকেল|সন্ধ্যা|shondha|sondha)/.test(text);
  const night = /(rat|ratri|night|রাত)/.test(text);

  if (hour <= 12) {
    if (afternoon && hour !== 12) hour += 12;
    else if (evening && hour < 12) hour += 12;
    else if (night && hour < 12) hour += 12;
    else if (morning && hour === 12) hour = 0;
  }

  const tomorrow = /(kal|kalke|tomorrow|আগামীকাল|কাল)/.test(text);
  return { hour: hour % 24, minute, tomorrow };
}

function extractSearchQuery(text: string): string {
  const cleaned = text
    .replace(/(google|youtube|browser|chrome|গুগল|ইউটিউব)/g, ' ')
    .replace(/\b(e|te|de|in|on|for|the|a|an|please|plz|ta|te)\b/g, ' ')
    .replace(/(search|khojo|khoj|find|dekho|koro|kor|do|করো|খোঁজ|সার্চ)/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned;
}

const RULES: Rule[] = [
  {
    // Must precede OPEN_APP: "alarm" is also an alias of the clock app.
    name: 'SET_ALARM',
    run: (text) => {
      if (!/(alarm|অ্যালার্ম|এলার্ম)/.test(text)) return null;
      const time = parseClockTime(text);
      if (!time) return null;
      const action: ParsedAction = { type: 'SET_ALARM', hour: time.hour, minute: time.minute };
      return time.tomorrow ? { ...action, relative_day: 'tomorrow' } : action;
    },
  },
  {
    name: 'SET_TIMER',
    run: (text) => {
      if (!/(timer|টাইমার)/.test(text)) return null;
      const seconds = parseDuration(text);
      return seconds === null ? null : { type: 'SET_TIMER', duration_seconds: seconds };
    },
  },
  {
    name: 'READ_SCREEN',
    run: (text) => {
      const readsScreen =
        /(read (the )?screen|screen (ta )?(poro|porho|pora|read)|স্ক্রিন.*(পড়|পড়ো)|পড়ে শোনাও)/.test(text);
      return readsScreen ? { type: 'READ_SCREEN', scope: 'visible' } : null;
    },
  },
  {
    name: 'GO_HOME',
    run: (text) => {
      const home = /(go home|home screen|home e jao|home jao|হোম স্ক্রিন|হোমে যাও|হোম এ যাও)/.test(text);
      return home ? { type: 'GO_HOME' } : null;
    },
  },
  {
    name: 'GO_BACK',
    run: (text) => {
      const back = /(go back|back jao|back koro|back e jao|পিছনে যাও|ব্যাক|আগের স্ক্রিন)/.test(text);
      return back || /^back$/.test(text.trim()) ? { type: 'GO_BACK' } : null;
    },
  },
  {
    name: 'OPEN_URL_EXPLICIT',
    run: (text) => {
      const match = /\b((?:https?:\/\/)?[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+(?:\/[^\s]*)?)/i.exec(text);
      if (!match?.[1]) return null;
      const candidate = match[1];
      // Require a recognisable TLD so "open koro" style text is not treated as a host.
      if (!/\.(com|net|org|io|dev|app|co|gov|edu|bd|info|me|ai|tv)\b/i.test(candidate)) return null;
      return { type: 'OPEN_URL', url: candidate };
    },
  },
  {
    name: 'OPEN_URL_SEARCH',
    run: (text) => {
      if (!/(search|সার্চ|খোঁজ|khojo|khoj)/.test(text)) return null;
      const query = extractSearchQuery(text);
      const url =
        query.length > 0
          ? `https://www.google.com/search?q=${encodeURIComponent(query)}`
          : 'https://www.google.com';
      return { type: 'OPEN_URL', url };
    },
  },
  {
    name: 'PLAY_MEDIA',
    run: (text) => {
      const match = /(?:play|chalao|chalu koro|বাজাও|চালাও)\s+(.{2,120})/.exec(text);
      const query = match?.[1]?.replace(/\b(koro|kor|dao|please|plz)\b/g, ' ').replace(/\s+/g, ' ').trim();
      if (!query || query.length < 2) return null;
      const app = findAppInText(text);
      const action: ParsedAction = { type: 'PLAY_MEDIA', query };
      if (app?.slug === 'youtube') return { ...action, app: 'youtube' };
      if (app?.slug === 'spotify') return { ...action, app: 'spotify' };
      return action;
    },
  },
  {
    name: 'CLOSE_APP',
    run: (text) => {
      if (!hasWord(text, CLOSE_VERBS)) return null;
      const app = findAppInText(text);
      return app ? { type: 'CLOSE_APP', app: app.slug } : null;
    },
  },
  {
    name: 'OPEN_APP',
    run: (text) => {
      const app = findAppInText(text);
      if (!app) return null;
      // An app name alone ("YouTube") is treated as "open it"; otherwise require a verb.
      const isBareAppName = text.trim().length <= app.slug.length + 3;
      if (!hasWord(text, OPEN_VERBS) && !isBareAppName) return null;
      return { type: 'OPEN_APP', app: app.slug };
    },
  },
];

export interface FallbackResult {
  action: ParsedAction;
  rule: string;
}

/**
 * @param normalizedText wake-word-stripped command text
 * @returns the matched low-risk action, or null when nothing matches confidently
 */
export function parseFallback(normalizedText: string): FallbackResult | null {
  const text = normalizedText.toLowerCase().trim();
  if (text.length === 0) return null;

  for (const rule of RULES) {
    const action = rule.run(text);
    if (action) return { action, rule: rule.name };
  }
  return null;
}
