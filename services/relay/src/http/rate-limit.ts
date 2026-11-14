// In-memory token buckets. Keys (client address or token digest) are never persisted or logged.
import type { RateRule } from '../config.ts';

const MAX_TRACKED_KEYS = 50_000;

export class RateLimiter {
  private readonly buckets = new Map<string, { tokens: number; at: number }>();

  /** Consumes one token. Returns seconds to wait, or 0 when allowed. */
  take(key: string, rule: RateRule, now: number = Date.now()): number {
    const bucket = this.refreshed(key, rule, now);
    if (bucket.tokens < 1) return Math.ceil(((1 - bucket.tokens) * 60) / rule.refillPerMinute);
    bucket.tokens -= 1;
    return 0;
  }

  /** Reads without consuming, for the budget charged only after a failed authentication. */
  exhausted(key: string, rule: RateRule, now: number = Date.now()): boolean {
    return this.buckets.has(key) && this.refreshed(key, rule, now).tokens < 1;
  }

  private refreshed(key: string, rule: RateRule, now: number) {
    let bucket = this.buckets.get(key);
    if (!bucket) {
      if (this.buckets.size >= MAX_TRACKED_KEYS) this.buckets.clear();
      bucket = { tokens: rule.capacity, at: now };
      this.buckets.set(key, bucket);
    }
    bucket.tokens = Math.min(rule.capacity, bucket.tokens + ((now - bucket.at) / 60000) * rule.refillPerMinute);
    bucket.at = now;
    return bucket;
  }
}
