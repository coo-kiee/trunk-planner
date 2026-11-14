// Semantic tones used by <Status>. Each maps to a CSS alias in semantic-tokens.css, which resolves to a generated token.
export type StatusTone = 'pending' | 'sending' | 'received' | 'accepted' | 'conflict' | 'stale' | 'unverified' | 'clear';

/** Machine-readable tone → generated token map. The DS check compares it with what the browser actually computes. */
export const TONE_TOKENS: Record<StatusTone, string> = {
  pending: '--cargo-color-unverified',
  sending: '--cargo-color-accent',
  received: '--cargo-color-uncertain',
  accepted: '--cargo-color-clear',
  conflict: '--cargo-color-conflict',
  stale: '--cargo-color-stale',
  unverified: '--cargo-color-unverified',
  clear: '--cargo-color-clear',
};
