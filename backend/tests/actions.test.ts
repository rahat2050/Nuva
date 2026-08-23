import { describe, expect, it } from 'vitest';
import { ACTION_META, actionSchema, isRegisteredActionType, safeUrl } from '../lib/actions';
import { ACTION_TYPES } from '../types/action';

describe('action registry', () => {
  it('registers exactly the 15 actions from the master prompt §8', () => {
    expect(ACTION_TYPES).toHaveLength(15);
    expect([...ACTION_TYPES]).toEqual([
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
    ]);
  });

  it('has metadata for every registered action', () => {
    for (const type of ACTION_TYPES) {
      expect(ACTION_META[type]).toBeDefined();
      expect(ACTION_META[type].signature.length).toBeGreaterThan(0);
    }
  });

  it('rejects unregistered action types', () => {
    expect(isRegisteredActionType('OPEN_APP')).toBe(true);
    expect(isRegisteredActionType('DELETE_FILE')).toBe(false);
    expect(isRegisteredActionType('EXEC_SHELL')).toBe(false);
    expect(isRegisteredActionType(null)).toBe(false);
  });
});

describe('actionSchema', () => {
  it('accepts valid actions', () => {
    expect(actionSchema.safeParse({ type: 'GO_HOME' }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'OPEN_APP', app: 'YouTube' }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'SET_TIMER', duration_seconds: 600 }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'SCROLL', direction: 'down', amount: 3 }).success).toBe(true);
    expect(
      actionSchema.safeParse({ type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'ami ashchi' }).success,
    ).toBe(true);
  });

  it('lowercases app names for stable matching', () => {
    const parsed = actionSchema.parse({ type: 'OPEN_APP', app: '  YouTube ' });
    expect(parsed).toEqual({ type: 'OPEN_APP', app: 'youtube' });
  });

  it('rejects unknown keys so nothing extra reaches the executor', () => {
    const result = actionSchema.safeParse({ type: 'GO_HOME', shell: 'rm -rf /' });
    expect(result.success).toBe(false);
  });

  it('rejects out-of-range alarm times', () => {
    expect(actionSchema.safeParse({ type: 'SET_ALARM', hour: 25, minute: 0 }).success).toBe(false);
    expect(actionSchema.safeParse({ type: 'SET_ALARM', hour: 7, minute: 61 }).success).toBe(false);
    expect(actionSchema.safeParse({ type: 'SET_ALARM', hour: 7, minute: 0 }).success).toBe(true);
  });

  it('rejects timers outside 1..86400 seconds', () => {
    expect(actionSchema.safeParse({ type: 'SET_TIMER', duration_seconds: 0 }).success).toBe(false);
    expect(actionSchema.safeParse({ type: 'SET_TIMER', duration_seconds: 90_000 }).success).toBe(false);
  });

  it('requires a semantic target or a point for TAP', () => {
    expect(actionSchema.safeParse({ type: 'TAP' }).success).toBe(false);
    expect(actionSchema.safeParse({ type: 'TAP', target: { text: 'Send' } }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'TAP', point: { x: 0.5, y: 0.5 } }).success).toBe(true);
  });

  it('rejects an empty selector', () => {
    expect(actionSchema.safeParse({ type: 'TAP', target: {} }).success).toBe(false);
    expect(actionSchema.safeParse({ type: 'TAP', target: { index: 2 } }).success).toBe(false);
  });

  it('rejects out-of-range coordinates (fractions only)', () => {
    expect(actionSchema.safeParse({ type: 'TAP', point: { x: 540, y: 1200 } }).success).toBe(false);
  });

  it('requires a direction or both endpoints for SWIPE', () => {
    expect(actionSchema.safeParse({ type: 'SWIPE' }).success).toBe(false);
    expect(actionSchema.safeParse({ type: 'SWIPE', direction: 'up' }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'SWIPE', from: { x: 0.5, y: 0.8 }, to: { x: 0.5, y: 0.2 } }).success).toBe(true);
  });

  it('restricts SEND_MESSAGE to known messaging apps', () => {
    expect(
      actionSchema.safeParse({ type: 'SEND_MESSAGE', app: 'darkweb', contact: 'x', message: 'y' }).success,
    ).toBe(false);
  });

  it('rejects control characters in free text', () => {
    expect(actionSchema.safeParse({ type: 'TYPE_TEXT', text: 'hello\u0000world' }).success).toBe(false);
  });

  it('validates phone numbers', () => {
    expect(actionSchema.safeParse({ type: 'CALL_CONTACT', contact: 'Rahim', phone_number: '+8801711223344' }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'CALL_CONTACT', contact: 'Rahim', phone_number: 'DROP TABLE' }).success).toBe(false);
  });

  it('validates Android package hints', () => {
    expect(actionSchema.safeParse({ type: 'OPEN_APP', app: 'youtube', package: 'com.google.android.youtube' }).success).toBe(true);
    expect(actionSchema.safeParse({ type: 'OPEN_APP', app: 'youtube', package: 'not a package' }).success).toBe(false);
  });
});

describe('safeUrl', () => {
  it('accepts http and https', () => {
    expect(safeUrl.parse('https://www.google.com/search?q=dhaka')).toBe('https://www.google.com/search?q=dhaka');
    expect(safeUrl.parse('http://example.com/')).toBe('http://example.com/');
  });

  it('upgrades a bare host to https', () => {
    expect(safeUrl.parse('youtube.com')).toBe('https://youtube.com/');
  });

  it.each(['javascript:alert(1)', 'data:text/html,<script>', 'file:///etc/passwd', 'intent://scan#Intent;end'])(
    'rejects dangerous scheme %j',
    (input) => {
      expect(safeUrl.safeParse(input).success).toBe(false);
    },
  );
});
