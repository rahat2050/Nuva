/**
 * Typed errors with safe, user-facing speech in every supported language.
 *
 * §24: never fail silently, and every failure must be safe and recoverable.
 * The `speech` string is what NUVA says out loud; `message` is for developers.
 */
import type { Language } from '../types/action.js';

export const ERROR_CODES = [
  'BAD_REQUEST',
  'METHOD_NOT_ALLOWED',
  'UNAUTHORIZED',
  'RATE_LIMITED',
  'PAYLOAD_TOO_LARGE',
  'NOT_CONFIGURED',
  'AI_UNAVAILABLE',
  'AI_INVALID_OUTPUT',
  'UPSTREAM_TIMEOUT',
  'PERSISTENCE_FAILED',
  'NOT_FOUND',
  'INTERNAL',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

interface ErrorSpec {
  status: number;
  speech: Record<Language, string>;
}

const SPECS: Record<ErrorCode, ErrorSpec> = {
  BAD_REQUEST: {
    status: 400,
    speech: {
      en: "That request didn't look right, so I stopped.",
      bn: 'অনুরোধটি ঠিক ছিল না, তাই আমি থেমে গেছি।',
      banglish: 'Request ta thik chilo na, tai ami theme gechi.',
    },
  },
  METHOD_NOT_ALLOWED: {
    status: 405,
    speech: {
      en: 'That request method is not supported here.',
      bn: 'এই অনুরোধের ধরন এখানে সমর্থিত নয়।',
      banglish: 'Ei request method ta ekhane supported na.',
    },
  },
  UNAUTHORIZED: {
    status: 401,
    speech: {
      en: 'Please sign in to NUVA first.',
      bn: 'অনুগ্রহ করে আগে নুভাতে সাইন ইন করুন।',
      banglish: 'Please age NUVA te sign in korun.',
    },
  },
  RATE_LIMITED: {
    status: 429,
    speech: {
      en: "That's a lot of commands at once. Give me a moment.",
      bn: 'একসাথে অনেক কমান্ড এসেছে। একটু সময় দিন।',
      banglish: 'Ekshathe onek command eshe geche. Ektu somoy din.',
    },
  },
  PAYLOAD_TOO_LARGE: {
    status: 413,
    speech: {
      en: 'That command was too long for me to process.',
      bn: 'কমান্ডটি অনেক বড় হয়ে গেছে।',
      banglish: 'Command ta onek boro hoye gecche.',
    },
  },
  NOT_CONFIGURED: {
    status: 503,
    speech: {
      en: 'That feature is not configured on the NUVA server yet.',
      bn: 'এই সুবিধাটি এখনো নুভা সার্ভারে চালু করা হয়নি।',
      banglish: 'Ei feature ta ekhono NUVA server e configure kora hoy nai.',
    },
  },
  AI_UNAVAILABLE: {
    status: 502,
    speech: {
      en: "I can't reach the NUVA server right now.",
      bn: 'এখন নুভা সার্ভারে পৌঁছাতে পারছি না।',
      banglish: 'Ekhon NUVA server e pouchate parchi na.',
    },
  },
  AI_INVALID_OUTPUT: {
    status: 502,
    speech: {
      en: "I couldn't understand that command.",
      bn: 'আমি কমান্ডটি বুঝতে পারিনি।',
      banglish: 'Ami command ta bujhte parini.',
    },
  },
  UPSTREAM_TIMEOUT: {
    status: 504,
    speech: {
      en: 'That took too long, so I stopped.',
      bn: 'অনেক সময় লেগে যাচ্ছিল, তাই থেমে গেছি।',
      banglish: 'Onek somoy lege jacchilo, tai theme gechi.',
    },
  },
  PERSISTENCE_FAILED: {
    status: 500,
    speech: {
      en: "I couldn't save that just now.",
      bn: 'এটি এখন সংরক্ষণ করতে পারিনি।',
      banglish: 'Eta ekhon save korte parini.',
    },
  },
  NOT_FOUND: {
    status: 404,
    speech: {
      en: "I couldn't find what you asked for.",
      bn: 'আপনি যা চেয়েছেন তা খুঁজে পাইনি।',
      banglish: 'Apni ja cheyechen ta khuje painai.',
    },
  },
  INTERNAL: {
    status: 500,
    speech: {
      en: 'Something went wrong on my side.',
      bn: 'আমার দিকে কিছু একটা সমস্যা হয়েছে।',
      banglish: 'Amar dike kichu ekta somossa hoyeche.',
    },
  },
};

export class NuvaError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly details: unknown;
  /** Set to false for expected client errors so logs stay quiet. */
  readonly expected: boolean;

  constructor(code: ErrorCode, message: string, options: { details?: unknown; expected?: boolean; cause?: unknown } = {}) {
    super(message);
    this.name = 'NuvaError';
    this.code = code;
    this.status = SPECS[code].status;
    this.details = options.details;
    this.expected = options.expected ?? SPECS[code].status < 500;
    if (options.cause !== undefined) this.cause = options.cause;
  }

  speech(language: Language = 'en'): string {
    return SPECS[this.code].speech[language];
  }
}

export function speechForCode(code: ErrorCode, language: Language = 'en'): string {
  return SPECS[code].speech[language];
}

export function statusForCode(code: ErrorCode): number {
  return SPECS[code].status;
}

/** Normalises anything thrown into a NuvaError without leaking internals. */
export function toNuvaError(err: unknown): NuvaError {
  if (err instanceof NuvaError) return err;
  if (err instanceof Error) {
    if (err.name === 'AbortError' || err.name === 'TimeoutError') {
      return new NuvaError('UPSTREAM_TIMEOUT', err.message, { cause: err });
    }
    return new NuvaError('INTERNAL', err.message, { cause: err });
  }
  return new NuvaError('INTERNAL', 'Unknown error', { details: typeof err });
}
