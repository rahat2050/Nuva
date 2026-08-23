import { describe, expect, it } from 'vitest';
import { detectLanguage, normalizeCommand } from '../lib/normalize';
import { NuvaError } from '../lib/errors';

describe('normalizeCommand', () => {
  it('collapses whitespace and keeps the original as history text', () => {
    const result = normalizeCommand('  Nuva   YouTube   open   koro.  ');
    expect(result.text).toBe('Nuva YouTube open koro.');
    expect(result.normalized).toBe('YouTube open koro.');
  });

  it.each([
    ['Nuva YouTube open koro.', 'YouTube open koro.'],
    ['Hey Nuva, back jao', 'back jao'],
    ['hey nuva back jao', 'back jao'],
    ['OK Nuva: go home', 'go home'],
    ['নুভা হোম স্ক্রিনে যাও', 'হোম স্ক্রিনে যাও'],
  ])('strips the wake word from %j', (input, expected) => {
    const result = normalizeCommand(input);
    expect(result.normalized).toBe(expected);
    expect(result.wakeWordDetected).toBe(true);
  });

  it('does not strip a word that merely starts with the wake word', () => {
    const result = normalizeCommand('nuvaland open koro');
    expect(result.wakeWordDetected).toBe(false);
    expect(result.normalized).toBe('nuvaland open koro');
  });

  it('keeps the text when the wake word is the entire utterance', () => {
    const result = normalizeCommand('Nuva');
    expect(result.wakeWordDetected).toBe(true);
    expect(result.normalized).toBe('Nuva');
  });

  it('strips control characters', () => {
    const result = normalizeCommand('open\u0000 youtube\u0007');
    expect(result.text).toBe('open youtube');
  });

  it('rejects empty and non-string input', () => {
    expect(() => normalizeCommand('   ')).toThrow(NuvaError);
    expect(() => normalizeCommand(42)).toThrow(NuvaError);
    expect(() => normalizeCommand(null)).toThrow(NuvaError);
  });

  it('rejects oversized input with PAYLOAD_TOO_LARGE', () => {
    try {
      normalizeCommand('a'.repeat(1001));
      expect.unreachable('should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(NuvaError);
      expect((err as NuvaError).code).toBe('PAYLOAD_TOO_LARGE');
    }
  });
});

describe('detectLanguage', () => {
  it.each([
    ['YouTube open koro', 'banglish'],
    ['kal shokal 7 tay alarm dao', 'banglish'],
    ['Rahim ke message pathao', 'banglish'],
    ['ei screen ta poro', 'banglish'],
    ['হোম স্ক্রিনে যাও', 'bn'],
    ['গুগলে ঢাকার আবহাওয়া সার্চ করো', 'bn'],
    ['open youtube', 'en'],
    ['set a 10 minute timer', 'en'],
    ['go back', 'en'],
  ])('detects %j as %s', (input, expected) => {
    expect(detectLanguage(input)).toBe(expected);
  });

  it('treats mixed script as bn, because the user clearly reads Bengali script', () => {
    expect(detectLanguage('YouTube খোলো')).toBe('bn');
    expect(detectLanguage('WhatsApp e message পাঠাও')).toBe('bn');
  });
});
