import { describe, expect, it } from 'vitest';
import { assessRisk, maxRisk } from '../lib/risk';
import type { ParsedAction } from '../lib/actions';

describe('maxRisk', () => {
  it('never lowers a risk level', () => {
    expect(maxRisk('low', 'high')).toBe('high');
    expect(maxRisk('high', 'low')).toBe('high');
    expect(maxRisk('medium', 'low')).toBe('medium');
  });
});

describe('assessRisk — baselines (§11)', () => {
  it.each<[ParsedAction, string]>([
    [{ type: 'GO_HOME' }, 'go home'],
    [{ type: 'GO_BACK' }, 'go back'],
    [{ type: 'OPEN_APP', app: 'youtube' }, 'youtube open koro'],
    [{ type: 'SET_TIMER', duration_seconds: 600 }, '10 minute timer'],
    [{ type: 'SET_ALARM', hour: 7, minute: 0 }, 'kal shokal 7 tay alarm dao'],
    [{ type: 'OPEN_URL', url: 'https://www.google.com/' }, 'google kholo'],
    [{ type: 'READ_SCREEN', scope: 'visible' }, 'ei screen ta poro'],
  ])('classifies %o as low risk without confirmation', (action, commandText) => {
    const assessment = assessRisk(action, { commandText });
    expect(assessment.risk).toBe('low');
    expect(assessment.requiresConfirmation).toBe(false);
  });

  it.each<[ParsedAction, string]>([
    [{ type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'ami ashchi' }, 'Rahim ke message pathao'],
    [{ type: 'CALL_CONTACT', contact: 'Rahim' }, 'Rahim ke call dao'],
  ])('classifies %o as medium risk requiring confirmation', (action, commandText) => {
    const assessment = assessRisk(action, { commandText });
    expect(assessment.risk).toBe('medium');
    expect(assessment.requiresConfirmation).toBe(true);
  });
});

describe('assessRisk — escalation', () => {
  it('escalates money transfers to high', () => {
    const assessment = assessRisk(
      { type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Karim', message: 'bkash e 5000 taka pathao' },
      { commandText: 'Karim ke bkash e 5000 taka pathao' },
    );
    expect(assessment.risk).toBe('high');
    expect(assessment.requiresConfirmation).toBe(true);
  });

  it.each([
    'amar password change koro',
    'otp ta send koro',
    'account delete koro',
    'factory reset koro',
    'নগদ দিয়ে টাকা পাঠাও',
  ])('escalates %j to high even for a low-risk action shape', (commandText) => {
    const assessment = assessRisk({ type: 'GO_HOME' }, { commandText });
    expect(assessment.risk).toBe('high');
    expect(assessment.requiresConfirmation).toBe(true);
  });

  it('escalates destructive-sounding requests to at least medium', () => {
    const assessment = assessRisk({ type: 'TAP', target: { text: 'Delete' } }, { commandText: 'delete this photo' });
    expect(assessment.risk).toBe('medium');
    expect(assessment.requiresConfirmation).toBe(true);
  });

  it('escalates financial apps', () => {
    const assessment = assessRisk({ type: 'OPEN_APP', app: 'bkash' }, { commandText: 'bkash kholo' });
    // "bkash" is also a high-risk term, so opening it always needs confirmation.
    expect(assessment.requiresConfirmation).toBe(true);
    expect(assessment.reasons.join(' ')).toMatch(/bkash/);
  });

  it('escalates coordinate-only taps because they are unverifiable', () => {
    const assessment = assessRisk({ type: 'TAP', point: { x: 0.5, y: 0.5 } }, { commandText: 'tap there' });
    expect(assessment.risk).toBe('medium');
    expect(assessment.reasons.join(' ')).toMatch(/coordinate fallback/);
  });

  it('does not escalate a semantic tap', () => {
    const assessment = assessRisk({ type: 'TAP', target: { resource_id: 'com.app:id/play' } }, { commandText: 'play koro' });
    expect(assessment.risk).toBe('low');
  });

  it('escalates URLs pointing at loopback or the local network', () => {
    expect(assessRisk({ type: 'OPEN_URL', url: 'http://127.0.0.1:8080/' }, { commandText: 'open it' }).risk).toBe('high');
    expect(assessRisk({ type: 'OPEN_URL', url: 'http://192.168.0.1/' }, { commandText: 'open router' }).risk).toBe('high');
  });
});

describe('assessRisk — the model can raise but never lower risk (§26)', () => {
  it('accepts a model escalation', () => {
    const assessment = assessRisk({ type: 'GO_HOME' }, { commandText: 'go home', modelRisk: 'high' });
    expect(assessment.risk).toBe('high');
    expect(assessment.requiresConfirmation).toBe(true);
  });

  it('ignores a model attempt to downgrade a medium-risk action', () => {
    const assessment = assessRisk(
      { type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'hi' },
      { commandText: 'Rahim ke message pathao', modelRisk: 'low', modelRequiresConfirmation: false },
    );
    expect(assessment.risk).toBe('medium');
    expect(assessment.requiresConfirmation).toBe(true);
  });

  it('honours a model confirmation request on a low-risk action', () => {
    const assessment = assessRisk({ type: 'GO_HOME' }, { commandText: 'go home', modelRequiresConfirmation: true });
    expect(assessment.risk).toBe('low');
    expect(assessment.requiresConfirmation).toBe(true);
  });

  it('still audits risk when there is no action', () => {
    const assessment = assessRisk(null, { commandText: 'bkash diye taka pathao' });
    expect(assessment.risk).toBe('high');
  });
});
