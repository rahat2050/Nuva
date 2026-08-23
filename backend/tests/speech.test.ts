import { describe, expect, it } from 'vitest';
import { confirmationPrompt, notUnderstoodSpeech, speechForAction, unsupportedSpeech } from '../lib/speech';
import { appLabel } from '../lib/apps';
import { LANGUAGES } from '../types/action';
import type { ParsedAction } from '../lib/actions';

describe('appLabel', () => {
  it('uses brand-correct casing for known apps', () => {
    expect(appLabel('youtube')).toBe('YouTube');
    expect(appLabel('whatsapp')).toBe('WhatsApp');
    expect(appLabel('tiktok')).toBe('TikTok');
    expect(appLabel('bkash')).toBe('bKash');
    expect(appLabel('play_store')).toBe('Play Store');
  });

  it('title-cases unknown apps', () => {
    expect(appLabel('some_new_app')).toBe('Some New App');
  });
});

describe('speechForAction', () => {
  const samples: ParsedAction[] = [
    { type: 'OPEN_APP', app: 'youtube' },
    { type: 'CLOSE_APP', app: 'whatsapp' },
    { type: 'GO_HOME' },
    { type: 'GO_BACK' },
    { type: 'TAP', target: { text: 'Play' } },
    { type: 'TYPE_TEXT', text: 'hello' },
    { type: 'SWIPE', direction: 'up' },
    { type: 'SCROLL', direction: 'down' },
    { type: 'CALL_CONTACT', contact: 'Rahim' },
    { type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'hi' },
    { type: 'SET_ALARM', hour: 7, minute: 0, relative_day: 'tomorrow' },
    { type: 'SET_TIMER', duration_seconds: 600 },
    { type: 'OPEN_URL', url: 'https://www.google.com/search?q=dhaka' },
    { type: 'PLAY_MEDIA', query: 'rabindra sangeet' },
    { type: 'READ_SCREEN', scope: 'visible' },
  ];

  it('produces non-empty speech for every action in every language', () => {
    for (const action of samples) {
      for (const language of LANGUAGES) {
        const speech = speechForAction(action, language);
        expect(speech.length, `${action.type}/${language}`).toBeGreaterThan(0);
      }
    }
  });

  it('speaks app names with correct casing', () => {
    expect(speechForAction({ type: 'OPEN_APP', app: 'youtube' }, 'en')).toBe('Opening YouTube.');
    expect(speechForAction({ type: 'OPEN_APP', app: 'youtube' }, 'banglish')).toBe('YouTube khulchi.');
    expect(speechForAction({ type: 'OPEN_APP', app: 'youtube' }, 'bn')).toBe('YouTube খুলছি।');
  });

  it('formats alarms and timers readably', () => {
    expect(speechForAction({ type: 'SET_ALARM', hour: 7, minute: 5 }, 'en')).toBe('Alarm set for 07:05.');
    expect(speechForAction({ type: 'SET_TIMER', duration_seconds: 600 }, 'en')).toBe('Timer set for 10 minutes.');
    expect(speechForAction({ type: 'SET_TIMER', duration_seconds: 45 }, 'en')).toBe('Timer set for 45 seconds.');
  });

  it('says the host, not the whole URL', () => {
    expect(speechForAction({ type: 'OPEN_URL', url: 'https://www.google.com/search?q=x' }, 'en')).toBe(
      'Opening google.com.',
    );
  });

  it('uses Bengali script for bn and Latin for banglish', () => {
    expect(speechForAction({ type: 'GO_BACK' }, 'bn')).toMatch(/[\u0980-\u09FF]/);
    expect(speechForAction({ type: 'GO_BACK' }, 'banglish')).not.toMatch(/[\u0980-\u09FF]/);
    expect(speechForAction({ type: 'GO_BACK' }, 'en')).toBe('Going back.');
  });
});

describe('confirmationPrompt', () => {
  it('always asks a question (§24)', () => {
    const risky: ParsedAction[] = [
      { type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'hi' },
      { type: 'CALL_CONTACT', contact: 'Rahim' },
      { type: 'OPEN_APP', app: 'bkash' },
      { type: 'TAP', point: { x: 0.5, y: 0.5 } },
    ];
    for (const action of risky) {
      for (const language of LANGUAGES) {
        expect(confirmationPrompt(action, language), `${action.type}/${language}`).toMatch(/[?？]$/);
      }
    }
  });

  it('names the contact so the user knows what they are approving', () => {
    expect(confirmationPrompt({ type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'hi' }, 'en')).toContain(
      'Rahim',
    );
  });
});

describe('fallback speech', () => {
  it('has text in every language', () => {
    for (const language of LANGUAGES) {
      expect(unsupportedSpeech(language).length).toBeGreaterThan(0);
      expect(notUnderstoodSpeech(language).length).toBeGreaterThan(0);
    }
  });
});
