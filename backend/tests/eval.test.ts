/**
 * Evaluation dataset integrity (roadmap follow-up: model evaluation harness).
 *
 * The harness in scripts/eval-models.ts is only meaningful if every expected
 * action in scripts/eval-dataset.json is itself a valid registry action. These
 * tests refuse to let a malformed case into the suite — so the dataset can
 * never silently drift away from the frozen contract.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateAction } from '../lib/validate';
import { ACTION_TYPES, RISK_LEVELS, LANGUAGES, type ActionType } from '../types/action';

const here = dirname(fileURLToPath(import.meta.url));

interface EvalCase {
  id: string;
  text: string;
  language: string;
  expect: {
    intent: string;
    risk: string;
    action?: Record<string, unknown>;
  };
}

const dataset = JSON.parse(readFileSync(resolve(here, '../scripts/eval-dataset.json'), 'utf8')) as EvalCase[];

describe('eval-dataset.json', () => {
  it('has at least 30 cases with unique ids', () => {
    expect(dataset.length).toBeGreaterThanOrEqual(30);
    const ids = new Set(dataset.map((c) => c.id));
    expect(ids.size).toBe(dataset.length);
  });

  it('covers every one of the 15 registered actions plus UNSUPPORTED', () => {
    const intents = new Set(dataset.map((c) => c.expect.intent));
    for (const action of ACTION_TYPES) {
      expect(intents.has(action as string)).toBe(true);
    }
    expect(intents.has('UNSUPPORTED')).toBe(true);
  });

  it('covers Bangla, Banglish and English', () => {
    const languages = new Set(dataset.map((c) => c.language));
    for (const language of LANGUAGES) {
      expect(languages.has(language as string)).toBe(true);
    }
  });

  it('uses only valid fields on every case', () => {
    for (const testCase of dataset) {
      expect(typeof testCase.id).toBe('string');
      expect(testCase.id.length).toBeGreaterThan(0);
      expect(typeof testCase.text).toBe('string');
      expect(testCase.text.length).toBeGreaterThan(0);
      expect(testCase.text.length).toBeLessThanOrEqual(1000);
      expect([...LANGUAGES, 'auto']).toContain(testCase.language);
      expect([...ACTION_TYPES, 'UNSUPPORTED']).toContain(testCase.expect.intent);
      expect([...RISK_LEVELS]).toContain(testCase.expect.risk);
    }
  });

  it('only contains expected actions that pass the strict registry schema', () => {
    const withActions = dataset.filter((c) => c.expect.action !== undefined);
    expect(withActions.length).toBeGreaterThanOrEqual(15);

    for (const testCase of withActions) {
      const outcome = validateAction(testCase.expect.action);
      expect(outcome.ok, `case ${testCase.id} has an invalid expected action`).toBe(true);
      expect((testCase.expect.action as { type?: string }).type).toBe(testCase.expect.intent);
    }
  });

  it('contains high-risk refusals for money transfer, deletion and password changes', () => {
    const refusals = dataset.filter((c) => c.expect.intent === 'UNSUPPORTED');
    expect(refusals.length).toBeGreaterThanOrEqual(4);
    expect(dataset.some((c) => c.id === 'refuse-money-transfer' && c.expect.risk === 'high')).toBe(true);
    expect(dataset.some((c) => c.id === 'refuse-shell' && c.expect.risk === 'high')).toBe(true);
    expect(dataset.some((c) => c.id === 'refuse-password-bangla' && c.expect.risk === 'high')).toBe(true);
  });

  it('gives every executable case a low/medium risk matching the registry baselines', () => {
    const mediumByDefault: ReadonlySet<string> = new Set(['CALL_CONTACT', 'SEND_MESSAGE'] as ActionType[]);
    for (const testCase of dataset) {
      if (testCase.expect.intent === 'UNSUPPORTED') continue;
      const expected = mediumByDefault.has(testCase.expect.intent as ActionType) ? 'medium' : 'low';
      expect(testCase.expect.risk, `case ${testCase.id}`).toBe(expected);
    }
  });
});
