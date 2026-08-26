import { describe, expect, it } from 'vitest';
import { parseFallback } from '../lib/fallbackParser';
import { validateAction } from '../lib/validate';
import { assessRisk } from '../lib/risk';
import { ACTION_META } from '../lib/actions';

describe('parseFallback', () => {
  it('parses app launches', () => {
    expect(parseFallback('YouTube open koro.')?.action).toEqual({ type: 'OPEN_APP', app: 'youtube' });
    expect(parseFallback('open whatsapp')?.action).toEqual({ type: 'OPEN_APP', app: 'whatsapp' });
    expect(parseFallback('ফেসবুক খোলো')?.action).toEqual({ type: 'OPEN_APP', app: 'facebook' });
  });

  it('parses navigation', () => {
    expect(parseFallback('back jao')?.action).toEqual({ type: 'GO_BACK' });
    expect(parseFallback('go home')?.action).toEqual({ type: 'GO_HOME' });
    expect(parseFallback('home e jao')?.action).toEqual({ type: 'GO_HOME' });
  });

  it('parses read screen', () => {
    expect(parseFallback('ei screen ta poro')?.action).toEqual({ type: 'READ_SCREEN', scope: 'visible' });
    expect(parseFallback('read the screen')?.action).toEqual({ type: 'READ_SCREEN', scope: 'visible' });
  });

  it('parses timers in several units and languages', () => {
    expect(parseFallback('10 minute er timer set koro')?.action).toEqual({ type: 'SET_TIMER', duration_seconds: 600 });
    expect(parseFallback('set a 30 second timer')?.action).toEqual({ type: 'SET_TIMER', duration_seconds: 30 });
    expect(parseFallback('2 ghonta timer dao')?.action).toEqual({ type: 'SET_TIMER', duration_seconds: 7200 });
  });

  it('parses alarms including Banglish time-of-day words', () => {
    expect(parseFallback('kal shokal 7 tay alarm dao')?.action).toEqual({
      type: 'SET_ALARM',
      hour: 7,
      minute: 0,
      relative_day: 'tomorrow',
    });
    expect(parseFallback('rat 9 tay alarm dao')?.action).toEqual({ type: 'SET_ALARM', hour: 21, minute: 0 });
    expect(parseFallback('alarm set koro 6:30')?.action).toEqual({ type: 'SET_ALARM', hour: 6, minute: 30 });
  });

  it('prefers SET_ALARM over launching the clock app', () => {
    // "alarm" is also an alias of the clock app — rule order matters.
    expect(parseFallback('kal shokal 7 tay alarm dao')?.action.type).toBe('SET_ALARM');
  });

  it('turns searches into an OPEN_URL', () => {
    const result = parseFallback('google e dhaka weather search koro');
    expect(result?.action.type).toBe('OPEN_URL');
    if (result?.action.type === 'OPEN_URL') {
      expect(result.action.url).toContain('https://www.google.com/search?q=');
      expect(result.action.url).toContain('dhaka');
    }
  });

  it('routes current information questions to a live web source', () => {
    for (const phrase of ['ajker weather kemon', 'latest news ki', 'cricket live score koto']) {
      const result = parseFallback(phrase);
      expect(result?.rule, phrase).toBe('OPEN_URL_LIVE_INFO');
      expect(result?.action.type, phrase).toBe('OPEN_URL');
      if (result?.action.type === 'OPEN_URL') {
        expect(result.action.url).toContain('https://www.google.com/search?q=');
      }
    }
  });

  it('routes factual and daily how-to questions to web knowledge', () => {
    for (const phrase of [
      'photosynthesis ki',
      'chicken biryani recipe',
      'how to tie a tie',
      'parcel tracking ZX123',
      'passport application',
      'internet speed test',
      'passport ki kagoj lagbe',
      'excel tutorial',
      'washing machine repair',
    ]) {
      const result = parseFallback(phrase);
      expect(result?.rule, phrase).toBe('OPEN_URL_KNOWLEDGE');
      expect(result?.action.type, phrase).toBe('OPEN_URL');
    }
  });

  it('opens explicit URLs', () => {
    const result = parseFallback('open youtube.com/feed');
    expect(result?.action.type).toBe('OPEN_URL');
  });

  it('parses media playback', () => {
    const result = parseFallback('youtube te play koro rabindra sangeet');
    expect(result?.action.type).toBe('PLAY_MEDIA');
  });

  it('returns null when unsure', () => {
    expect(parseFallback('amake ekta kobita likhe dao')).toBeNull();
    expect(parseFallback('tumi kemon acho')).toBeNull();
    expect(parseFallback('')).toBeNull();
  });

  it('NEVER produces an action that acts on someone else', () => {
    // The safety contract: the offline parser must not be able to message,
    // call, tap or type on the user's behalf.
    const forbidden = new Set(['SEND_MESSAGE', 'CALL_CONTACT', 'TAP', 'TYPE_TEXT', 'SWIPE']);
    const probes = [
      'Rahim ke WhatsApp e message pathao je ami ashchi',
      'Karim ke call dao',
      'bkash diye 5000 taka pathao',
      'send money to Rahim',
      'type my password',
      'tap the send money button',
      'amar account delete koro',
    ];
    for (const probe of probes) {
      const result = parseFallback(probe);
      if (result) expect(forbidden.has(result.action.type)).toBe(false);
    }
  });

  it('only ever emits actions whose registry baseline is low risk', () => {
    const probes = [
      'YouTube open koro',
      'back jao',
      'go home',
      'read the screen',
      '10 minute timer',
      'kal shokal 7 tay alarm dao',
      'google e search koro',
      'play koro rabindra sangeet',
      'bondho koro youtube',
    ];
    for (const probe of probes) {
      const result = parseFallback(probe);
      if (!result) continue;
      expect(ACTION_META[result.action.type].baseRisk).toBe('low');
    }
  });

  it('produces output that always passes the validator', () => {
    const probes = [
      'YouTube open koro',
      'back jao',
      'go home',
      'ei screen ta poro',
      '10 minute er timer set koro',
      'kal shokal 7 tay alarm dao',
      'google e dhaka weather search koro',
      'open youtube.com',
      'play koro rabindra sangeet',
    ];
    for (const probe of probes) {
      const result = parseFallback(probe);
      expect(result, `no match for ${probe}`).not.toBeNull();
      if (!result) continue;
      const validated = validateAction(result.action);
      expect(validated.ok, `${probe} → ${JSON.stringify(result.action)}`).toBe(true);
      if (validated.ok) {
        expect(assessRisk(validated.action, { commandText: probe }).risk).not.toBe('high');
      }
    }
  });
});
