/**
 * RISK CHECK — step 7 of the golden pipeline (§30), implementing §11.
 *
 * The model's own `risk` field is treated as advisory only. The server always
 * recomputes risk and takes the MAXIMUM of (registry baseline, keyword
 * escalation, model claim). Risk can therefore only ever be raised by the
 * model, never lowered — a prompt-injected `"risk":"low"` cannot disarm a
 * confirmation (§26: never bypass confirmation).
 */
import { isSensitiveApp } from './apps';
import type { ParsedAction } from './actions';
import { ACTION_META } from './actions';
import type { RiskLevel } from '../types/action';

const RISK_ORDER: Record<RiskLevel, number> = { low: 0, medium: 1, high: 2 };

export function maxRisk(a: RiskLevel, b: RiskLevel): RiskLevel {
  return RISK_ORDER[a] >= RISK_ORDER[b] ? a : b;
}

/**
 * HIGH: money movement, credentials, account destruction, security settings.
 * Terms are matched as substrings across English, Bangla script and Banglish.
 * False positives are acceptable here — the only consequence is an extra
 * confirmation prompt, which is always the safe direction to fail.
 */
const HIGH_RISK_TERMS = [
  // money / payments
  'send money', 'sendmoney', 'send taka', 'cash out', 'cashout', 'cash in', 'cashin',
  'money transfer', 'transfer money', 'bank transfer', 'wire transfer', 'payment', 'pay bill',
  'bill pay', 'billpay', 'transaction', 'bkash', 'bikash', 'nagad', 'rocket', 'upay',
  'credit card', 'debit card', 'card number', 'cvv', 'iban', 'paypal', 'binance', 'bitcoin',
  'crypto', 'loan', 'balance transfer', 'taka', 'recharge',
  'টাকা', 'ব্যাংক', 'বিকাশ', 'নগদ', 'রকেট', 'পেমেন্ট', 'লেনদেন', 'ক্যাশ আউট', 'রিচার্জ', 'বিল',
  // credentials / security
  'password', 'passwd', 'pass word', 'otp', 'one time password', 'verification code',
  'two factor', '2fa', 'authenticator', 'seed phrase', 'private key', 'recovery code',
  'security setting', 'security settings', 'screen lock', 'biometric', 'fingerprint',
  'পাসওয়ার্ড', 'ওটিপি', 'পিন কোড', 'ভেরিফিকেশন কোড', 'নিরাপত্তা',
  // account destruction
  'delete account', 'account delete', 'deactivate account', 'close account', 'remove account',
  'factory reset', 'erase all', 'wipe phone', 'format phone',
  'একাউন্ট ডিলিট', 'অ্যাকাউন্ট ডিলিট', 'ফ্যাক্টরি রিসেট',
];

/** MEDIUM: destructive-but-recoverable, or anything that leaves the device. */
const MEDIUM_RISK_TERMS = [
  'delete', 'remove', 'uninstall', 'clear all', 'post', 'publish', 'share', 'upload',
  'tweet', 'buy', 'order', 'checkout', 'subscribe', 'unsubscribe', 'forward',
  'ডিলিট', 'মুছে', 'মুছে ফেলো', 'আনইনস্টল', 'পোস্ট', 'শেয়ার', 'আপলোড', 'কিনো', 'অর্ডার',
];

/** Hosts that should never be opened without an explicit confirmation. */
const BLOCKED_URL_HOSTS = /^(localhost|127\.|0\.0\.0\.0|\[::1\]|169\.254\.)/i;
const PRIVATE_URL_HOSTS = /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/;

export interface RiskAssessment {
  risk: RiskLevel;
  requiresConfirmation: boolean;
  /** Human-readable justifications, surfaced in logs and the API response. */
  reasons: string[];
}

function scanTerms(haystack: string, terms: string[]): string[] {
  const hits: string[] = [];
  for (const term of terms) {
    if (haystack.includes(term)) hits.push(term);
  }
  return hits;
}

/** Collects every user-influenced string in the action, plus the raw command. */
function buildHaystack(action: ParsedAction | null, commandText: string): string {
  const parts: string[] = [commandText];
  if (action) {
    for (const [key, value] of Object.entries(action)) {
      if (key === 'type') continue;
      if (typeof value === 'string') parts.push(value);
      else if (value && typeof value === 'object') parts.push(JSON.stringify(value));
    }
  }
  return parts.join(' \n ').toLowerCase();
}

export function assessRisk(
  action: ParsedAction | null,
  options: { commandText: string; modelRisk?: RiskLevel; modelRequiresConfirmation?: boolean },
): RiskAssessment {
  const reasons: string[] = [];

  // 1. Registry baseline.
  let risk: RiskLevel = action ? ACTION_META[action.type].baseRisk : 'low';
  if (action && risk !== 'low') {
    reasons.push(`${action.type} is ${risk} risk by default`);
  }

  // 2. Keyword escalation over the command and every action field.
  const haystack = buildHaystack(action, options.commandText);
  const highHits = scanTerms(haystack, HIGH_RISK_TERMS);
  if (highHits.length > 0) {
    risk = maxRisk(risk, 'high');
    reasons.push(`sensitive terms detected: ${highHits.slice(0, 5).join(', ')}`);
  } else {
    const mediumHits = scanTerms(haystack, MEDIUM_RISK_TERMS);
    if (mediumHits.length > 0) {
      risk = maxRisk(risk, 'medium');
      reasons.push(`potentially destructive terms detected: ${mediumHits.slice(0, 5).join(', ')}`);
    }
  }

  // 3. Action-specific escalation.
  if (action) {
    if ((action.type === 'OPEN_APP' || action.type === 'CLOSE_APP') && isSensitiveApp(action.app)) {
      risk = maxRisk(risk, 'medium');
      reasons.push(`${action.app} is a financial app`);
    }
    if (action.type === 'OPEN_URL') {
      try {
        const host = new URL(action.url).hostname;
        if (BLOCKED_URL_HOSTS.test(host)) {
          risk = maxRisk(risk, 'high');
          reasons.push('URL points at a loopback or link-local address');
        } else if (PRIVATE_URL_HOSTS.test(host)) {
          risk = maxRisk(risk, 'high');
          reasons.push('URL points at a private network address');
        }
      } catch {
        // actionSchema already guarantees a parseable URL; ignore defensively.
      }
    }
    // A coordinate-only tap is unverifiable, so it can never be silently risky.
    if (action.type === 'TAP' && !action.target && action.point) {
      risk = maxRisk(risk, 'medium');
      reasons.push('tap uses a coordinate fallback instead of a semantic target');
    }
  }

  // 4. The model may raise, never lower.
  if (options.modelRisk) {
    const raised = maxRisk(risk, options.modelRisk);
    if (raised !== risk) reasons.push(`model reported ${options.modelRisk} risk`);
    risk = raised;
  }

  const requiresConfirmation = risk !== 'low' || options.modelRequiresConfirmation === true;
  if (requiresConfirmation && risk === 'low') {
    reasons.push('model requested confirmation');
  }

  return { risk, requiresConfirmation, reasons };
}
