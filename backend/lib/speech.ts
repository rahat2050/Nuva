/**
 * Deterministic TTS phrasing.
 *
 * The model normally supplies its own `speech`, but NUVA must still talk when
 * the model omits it or when the offline fallback parser runs. Every action has
 * a template in all three supported languages (§15, §25).
 */
import { appLabel } from './apps.js';
import type { ParsedAction } from './actions.js';
import type { Language } from '../types/action.js';

type Templates = Record<Language, string>;

function pick(templates: Templates, language: Language): string {
  return templates[language];
}

function clock(hour: number, minute: number): string {
  return `${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}`;
}

function humanDuration(seconds: number, language: Language): string {
  const minutes = Math.round(seconds / 60);
  if (seconds < 60) {
    return pick({ en: `${seconds} seconds`, bn: `${seconds} সেকেন্ড`, banglish: `${seconds} second` }, language);
  }
  if (minutes < 60) {
    return pick({ en: `${minutes} minutes`, bn: `${minutes} মিনিট`, banglish: `${minutes} minute` }, language);
  }
  const hours = Math.round(minutes / 60);
  return pick({ en: `${hours} hours`, bn: `${hours} ঘণ্টা`, banglish: `${hours} ghonta` }, language);
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** What NUVA says while performing the action. */
export function speechForAction(action: ParsedAction, language: Language): string {
  switch (action.type) {
    case 'OPEN_APP': {
      const name = appLabel(action.app);
      return pick({ en: `Opening ${name}.`, bn: `${name} খুলছি।`, banglish: `${name} khulchi.` }, language);
    }
    case 'CLOSE_APP': {
      const name = appLabel(action.app);
      return pick(
        { en: `Closing ${name}.`, bn: `${name} বন্ধ করছি।`, banglish: `${name} bondho korchi.` },
        language,
      );
    }
    case 'GO_HOME':
      return pick(
        { en: 'Going to the home screen.', bn: 'হোম স্ক্রিনে যাচ্ছি।', banglish: 'Home screen e jacchi.' },
        language,
      );
    case 'GO_BACK':
      return pick({ en: 'Going back.', bn: 'পিছনে যাচ্ছি।', banglish: 'Back jacchi.' }, language);
    case 'TAP':
      return pick({ en: 'Tapping that.', bn: 'ট্যাপ করছি।', banglish: 'Tap korchi.' }, language);
    case 'TYPE_TEXT':
      return pick({ en: 'Typing that.', bn: 'লিখছি।', banglish: 'Likhchi.' }, language);
    case 'SWIPE':
      return pick({ en: 'Swiping.', bn: 'স্বাইপ করছি।', banglish: 'Swipe korchi.' }, language);
    case 'SCROLL':
      return pick({ en: 'Scrolling.', bn: 'স্ক্রল করছি।', banglish: 'Scroll korchi.' }, language);
    case 'CALL_CONTACT':
      return pick(
        { en: `Calling ${action.contact}.`, bn: `${action.contact} কে কল করছি।`, banglish: `${action.contact} ke call korchi.` },
        language,
      );
    case 'SEND_MESSAGE':
      return pick(
        {
          en: `Sending your message to ${action.contact}.`,
          bn: `${action.contact} কে মেসেজ পাঠাচ্ছি।`,
          banglish: `${action.contact} ke message pathacchi.`,
        },
        language,
      );
    case 'SET_ALARM': {
      const time = clock(action.hour, action.minute);
      const tomorrow = action.relative_day === 'tomorrow';
      return pick(
        {
          en: `Alarm set for ${time}${tomorrow ? ' tomorrow' : ''}.`,
          bn: `${tomorrow ? 'কাল ' : ''}${time} টায় অ্যালার্ম দিয়েছি।`,
          banglish: `${tomorrow ? 'Kal ' : ''}${time} tay alarm diyechi.`,
        },
        language,
      );
    }
    case 'SET_TIMER': {
      const duration = humanDuration(action.duration_seconds, language);
      return pick(
        {
          en: `Timer set for ${duration}.`,
          bn: `${duration}ের টাইমার দিয়েছি।`,
          banglish: `${duration} er timer diyechi.`,
        },
        language,
      );
    }
    case 'OPEN_URL': {
      const host = hostOf(action.url);
      return pick({ en: `Opening ${host}.`, bn: `${host} খুলছি।`, banglish: `${host} khulchi.` }, language);
    }
    case 'PLAY_MEDIA':
      return pick(
        { en: `Playing ${action.query}.`, bn: `${action.query} চালাচ্ছি।`, banglish: `${action.query} chalacchi.` },
        language,
      );
    case 'READ_SCREEN':
      return pick({ en: 'Reading the screen.', bn: 'স্ক্রিন পড়ছি।', banglish: 'Screen porchi.' }, language);
    default: {
      const exhaustive: never = action;
      void exhaustive;
      return pick({ en: 'Working on it.', bn: 'করছি।', banglish: 'Korchi.' }, language);
    }
  }
}

/** What NUVA asks before a medium/high risk action (§24). */
export function confirmationPrompt(action: ParsedAction, language: Language): string {
  switch (action.type) {
    case 'SEND_MESSAGE':
      return pick(
        {
          en: `Do you want me to send this message to ${action.contact}?`,
          bn: `${action.contact} কে কি এই মেসেজটি পাঠাব?`,
          banglish: `${action.contact} ke ki ei message ta pathabo?`,
        },
        language,
      );
    case 'CALL_CONTACT':
      return pick(
        {
          en: `Should I call ${action.contact}?`,
          bn: `${action.contact} কে কল করব?`,
          banglish: `${action.contact} ke call korbo?`,
        },
        language,
      );
    case 'OPEN_APP': {
      const name = appLabel(action.app);
      return pick({ en: `Should I open ${name}?`, bn: `${name} খুলব?`, banglish: `${name} khulbo?` }, language);
    }
    default:
      return pick(
        {
          en: 'This looks sensitive. Should I go ahead?',
          bn: 'এটি সংবেদনশীল মনে হচ্ছে। আমি কি এগিয়ে যাব?',
          banglish: 'Eta sensitive mone hocche. Ami ki egiye jabo?',
        },
        language,
      );
  }
}

export function unsupportedSpeech(language: Language): string {
  return pick(
    {
      en: "I can't do that yet.",
      bn: 'এটি আমি এখনো করতে পারি না।',
      banglish: 'Eta ami ekhono korte pari na.',
    },
    language,
  );
}

export function notUnderstoodSpeech(language: Language): string {
  return pick(
    {
      en: "I couldn't understand that command.",
      bn: 'আমি কমান্ডটি বুঝতে পারিনি।',
      banglish: 'Ami command ta bujhte parini.',
    },
    language,
  );
}
