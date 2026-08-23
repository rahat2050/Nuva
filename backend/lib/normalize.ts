/**
 * TEXT NORMALIZATION — step 3 of the golden pipeline (§30).
 *
 * Responsibilities, deliberately narrow:
 *   1. Make the transcript safe and canonical (Unicode NFC, no control chars).
 *   2. Strip the wake word so "Nuva YouTube open koro" and "YouTube open koro"
 *      reach the AI identically.
 *   3. Detect the language so replies and error speech use the right script.
 *
 * It never translates and never guesses intent — that is the AI's job (§10).
 */
import { NuvaError } from './errors';
import type { Language } from '../types/action';

export const MAX_COMMAND_CHARS = 1000;

const BENGALI_RANGE = /[\u0980-\u09FF]/g;
const LATIN_LETTER = /[a-zA-Z]/;

/**
 * Wake-word spellings, including frequent ASR mis-hearings of "Nuva".
 * PHRASE 2's WakeWordService owns real detection; here we only strip the token
 * when SpeechRecognizer includes it in the transcript.
 */
const WAKE_PREFIXES = [
  'hey nuva',
  'hi nuva',
  'ok nuva',
  'okay nuva',
  'hey noova',
  'hey nova',
  'hey nuvaa',
  'হেই নুভা',
  'হ্যালো নুভা',
  'ওকে নুভা',
  'nuva',
  'noova',
  'nuvaa',
  'নুভা',
  'নুবা',
];

/** Banglish markers: Bangla function words/verbs written in Latin script. */
const BANGLISH_MARKERS = new Set([
  'koro', 'korbe', 'kore', 'korte', 'koro na', 'kori',
  'dao', 'dibe', 'de', 'diye', 'din',
  'jao', 'jabe', 'jai', 'jete',
  'pathao', 'pathai', 'pathiye', 'pathan',
  'kholo', 'khulo', 'khule', 'kholen',
  'bondho', 'bondo', 'bandho',
  'ache', 'achhe', 'ase', 'chilo',
  'ami', 'tumi', 'apni', 'amake', 'amar', 'tomar', 'apnar', 'tomake',
  'ke', 'ta', 'ti', 'ei', 'oi', 'eta', 'ota', 'era',
  'kal', 'aj', 'aaj', 'ajke', 'kalke', 'porshu',
  'shokal', 'sokal', 'bikal', 'bikel', 'rat', 'ratri', 'dupur', 'shondha', 'sondha',
  'tay', 'tar', 'theke', 'porjonto',
  'bolo', 'bol', 'bole', 'boldo',
  'dekho', 'dekha', 'dekhao', 'dekhi',
  'shono', 'sono', 'shune',
  'poro', 'porho', 'pora', 'likho', 'likhe',
  'ki', 'kobe', 'kothay', 'kotha', 'keno', 'kemon', 'koto', 'kar',
  'ekta', 'ektu', 'onek', 'ar', 'aro', 'abar',
  'na', 'nai', 'hobe', 'hoy', 'holo',
  'plz', 'taratari', 'jonno', 'diye', 'niye', 'somoy',
]);

export interface NormalizedCommand {
  /** Cleaned original (whitespace collapsed, NFC) — what we store as history. */
  text: string;
  /** Wake word removed — what we send to the AI. */
  normalized: string;
  language: Language;
  wakeWordDetected: boolean;
}

function stripControlChars(value: string): string {
  // eslint-disable-next-line no-control-regex
  return value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, ' ');
}

function collapseWhitespace(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

/** Removes a leading wake word, tolerating punctuation like "Nuva, ..." */
function stripWakeWord(value: string): { text: string; detected: boolean } {
  const lower = value.toLowerCase();
  for (const prefix of WAKE_PREFIXES) {
    if (!lower.startsWith(prefix)) continue;
    const rest = value.slice(prefix.length);
    // Only treat it as a wake word if a boundary follows, so "nuvaland" is safe.
    if (rest.length === 0 || /^[\s,.:;!?—-]/.test(rest)) {
      const cleaned = collapseWhitespace(rest.replace(/^[\s,.:;!?—-]+/, ''));
      return { text: cleaned, detected: true };
    }
  }
  return { text: value, detected: false };
}

export function detectLanguage(value: string): Language {
  const bengaliMatches = value.match(BENGALI_RANGE)?.length ?? 0;
  const hasLatin = LATIN_LETTER.test(value);

  // Any Bengali script at all means the user reads Bengali script, so NUVA
  // should answer in it — even for mixed input like "YouTube খোলো".
  // `banglish` is specifically Bangla written in LATIN letters.
  if (bengaliMatches > 0) return 'bn';
  if (!hasLatin) return 'en';

  const tokens = value.toLowerCase().split(/[^a-z]+/).filter((token) => token.length > 0);
  const markerHits = tokens.filter((token) => BANGLISH_MARKERS.has(token)).length;
  return markerHits > 0 ? 'banglish' : 'en';
}

/**
 * @throws NuvaError BAD_REQUEST when empty, PAYLOAD_TOO_LARGE when over the cap.
 */
export function normalizeCommand(raw: unknown): NormalizedCommand {
  if (typeof raw !== 'string') {
    throw new NuvaError('BAD_REQUEST', '`text` must be a string');
  }
  if (raw.length > MAX_COMMAND_CHARS) {
    throw new NuvaError('PAYLOAD_TOO_LARGE', `\`text\` exceeds ${MAX_COMMAND_CHARS} characters`);
  }

  const cleaned = collapseWhitespace(stripControlChars(raw.normalize('NFC')));
  if (cleaned.length === 0) {
    throw new NuvaError('BAD_REQUEST', '`text` must not be empty');
  }

  const { text: withoutWake, detected } = stripWakeWord(cleaned);
  // "Nuva" on its own is a valid attention word; keep it so the AI can greet.
  const normalized = withoutWake.length > 0 ? withoutWake : cleaned;

  return {
    text: cleaned,
    normalized,
    language: detectLanguage(normalized),
    wakeWordDetected: detected,
  };
}
