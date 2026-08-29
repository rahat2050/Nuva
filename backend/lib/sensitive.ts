/** Shared server-side secret handling boundary. Never log matching values. */
const CREDENTIAL_PATTERN = /(password|passwd|passcode|secret|bearer|access[_ -]?token|refresh[_ -]?token|api[_ -]?key|otp|one[ -]?time password|verification code|pin number|cvv|cvc|card number|private[_ -]?key|seed[_ -]?phrase|recovery code|পাসওয়ার্ড|ওটিপি|পিন নম্বর|সিভিভি)/i;

const TRANSACTION_PATTERN = /(send money|cash out|cash in|send taka|taka patha|tk patha|transfer money|money transfer|bank transfer|add money|mobile recharge|recharge (koro|korun)|pay (the )?bill|bill pay|payment (koro|korun|confirm)|make payment|card diye|card payment|authorize payment|purchase (koro|korun)|টাকা পাঠা|সেন্ড মানি|ক্যাশ আউট|ব্যাংক ট্রান্সফার|লেনদেন|পেমেন্ট কর|বিল পরিশোধ|রিচার্জ কর|কার্ড দিয়ে)/i;

export function containsCredentialTerms(value: string): boolean {
  return CREDENTIAL_PATTERN.test(value);
}

export function containsTransactionRequest(value: string): boolean {
  return TRANSACTION_PATTERN.test(value);
}
