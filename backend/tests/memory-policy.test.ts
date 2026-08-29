import { describe, expect, it } from 'vitest';
import { isSafeMemoryValue } from '../api/memory/index';
import { containsTransactionRequest } from '../lib/sensitive';

describe('memory value privacy policy', () => {
  it('accepts ordinary preferences', () => {
    expect(isSafeMemoryValue('Bangla replies and dark theme')).toBe(true);
    expect(isSafeMemoryValue('YouTube is my preferred music app')).toBe(true);
  });

  it.each([
    'my password is hunter2',
    'OTP 4321',
    'access token: abc.def.ghi',
    'card number 4111111111111111',
    'আমার পাসওয়ার্ড secret123',
    'ওটিপি ১২৩৪',
  ])('rejects credential-bearing value without logging it', (value) => {
    expect(isSafeMemoryValue(value)).toBe(false);
  });

  it('rejects empty and oversized values', () => {
    expect(isSafeMemoryValue('   ')).toBe(false);
    expect(isSafeMemoryValue('x'.repeat(4001))).toBe(false);
  });

  it('detects English, Banglish and Bangla transaction requests', () => {
    expect(containsTransactionRequest('send money to Rahim')).toBe(true);
    expect(containsTransactionRequest('bkash e 500 taka pathao')).toBe(true);
    expect(containsTransactionRequest('৫০০ টাকা পাঠাও')).toBe(true);
    expect(containsTransactionRequest('bkash kholo')).toBe(false);
  });
});
