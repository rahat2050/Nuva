import { describe, expect, it } from 'vitest';
import { extractJsonObject, validateModelOutput } from '../lib/validate';

describe('extractJsonObject', () => {
  it('extracts a plain object', () => {
    expect(extractJsonObject('{"a":1}')).toBe('{"a":1}');
  });

  it('strips markdown fences', () => {
    expect(extractJsonObject('```json\n{"a":1}\n```')).toBe('{"a":1}');
  });

  it('ignores prose around the object', () => {
    expect(extractJsonObject('Sure! {"a":1} hope that helps')).toBe('{"a":1}');
  });

  it('handles nested objects and braces inside strings', () => {
    const raw = '{"action":{"type":"TYPE_TEXT","text":"} not the end {"}}';
    expect(extractJsonObject(raw)).toBe(raw);
  });

  it('returns null when there is no object', () => {
    expect(extractJsonObject('I cannot help with that')).toBeNull();
    expect(extractJsonObject('{"unbalanced":')).toBeNull();
  });
});

describe('validateModelOutput', () => {
  it('accepts a well-formed envelope', () => {
    const outcome = validateModelOutput(
      JSON.stringify({
        intent: 'OPEN_APP',
        action: { type: 'OPEN_APP', app: 'youtube' },
        risk: 'low',
        requires_confirmation: false,
        confidence: 0.97,
        speech: 'YouTube khulchi.',
      }),
    );
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    expect(outcome.action).toEqual({ type: 'OPEN_APP', app: 'youtube', package: 'com.google.android.youtube' });
    expect(outcome.modelRisk).toBe('low');
    expect(outcome.confidence).toBe(0.97);
    expect(outcome.speech).toBe('YouTube khulchi.');
  });

  it('enriches known apps with a package hint', () => {
    const outcome = validateModelOutput({ intent: 'OPEN_APP', action: { type: 'OPEN_APP', app: 'whatsapp' } });
    expect(outcome.ok).toBe(true);
    if (outcome.ok && outcome.action.type === 'OPEN_APP') {
      expect(outcome.action.package).toBe('com.whatsapp');
    }
  });

  it('treats an unregistered action type as unsupported, not an error', () => {
    const outcome = validateModelOutput({ intent: 'DELETE_FILE', action: { type: 'DELETE_FILE', path: '/sdcard/a' } });
    expect(outcome).toMatchObject({ ok: false, kind: 'unsupported' });
    if (!outcome.ok) expect(outcome.reasons[0]).toContain('not in the NUVA action registry');
  });

  it('rejects an attempt to smuggle shell execution', () => {
    const outcome = validateModelOutput({
      intent: 'RUN_SHELL',
      action: { type: 'RUN_SHELL', command: 'rm -rf /' },
    });
    expect(outcome).toMatchObject({ ok: false, kind: 'unsupported' });
  });

  it('rejects extra keys on a registered action', () => {
    const outcome = validateModelOutput({
      intent: 'GO_HOME',
      action: { type: 'GO_HOME', shell: 'whoami' },
    });
    expect(outcome).toMatchObject({ ok: false, kind: 'unsupported' });
  });

  it('reports invalid JSON safely', () => {
    expect(validateModelOutput('not json at all')).toMatchObject({ ok: false, kind: 'invalid' });
    expect(validateModelOutput('{"broken": ')).toMatchObject({ ok: false, kind: 'invalid' });
  });

  it('honours an explicit UNSUPPORTED refusal', () => {
    const outcome = validateModelOutput({
      intent: 'UNSUPPORTED',
      action: null,
      risk: 'high',
      reason: 'money transfer requested',
    });
    expect(outcome).toMatchObject({ ok: false, kind: 'unsupported' });
    if (!outcome.ok) expect(outcome.reasons[0]).toBe('money transfer requested');
  });

  it('coerces stringly-typed booleans and risk casing', () => {
    const outcome = validateModelOutput({
      intent: 'SEND_MESSAGE',
      action: { type: 'SEND_MESSAGE', app: 'whatsapp', contact: 'Rahim', message: 'hi' },
      risk: 'MEDIUM',
      requires_confirmation: 'true',
      confidence: '0.8',
    });
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    expect(outcome.modelRisk).toBe('medium');
    expect(outcome.modelRequiresConfirmation).toBe(true);
    expect(outcome.confidence).toBe(0.8);
  });

  it('ignores unknown envelope-level keys but keeps the action strict', () => {
    const outcome = validateModelOutput({
      intent: 'GO_BACK',
      action: { type: 'GO_BACK' },
      hallucinated_field: 'whatever',
    });
    expect(outcome.ok).toBe(true);
  });

  it('rejects a missing action', () => {
    expect(validateModelOutput({ intent: 'OPEN_APP' })).toMatchObject({ ok: false, kind: 'invalid' });
  });

  it('clamps out-of-range confidence', () => {
    const outcome = validateModelOutput({ intent: 'GO_HOME', action: { type: 'GO_HOME' }, confidence: 42 });
    expect(outcome.ok).toBe(true);
    if (outcome.ok) expect(outcome.confidence).toBe(1);
  });
});
