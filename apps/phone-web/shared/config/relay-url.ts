// Validates the public relay address once on the server. It is a public URL, never a secret.
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

export type RelayUrlCheck = { ok: true; url: string } | { ok: false; reason: 'MISSING' | 'INVALID' | 'CLEARTEXT_NON_LOOPBACK' };

export function isLoopbackHost(hostname: string): boolean {
  return LOOPBACK_HOSTS.has(hostname);
}

/** https anywhere; http only on loopback, the explicit local development exception. */
export function readRelayBaseUrl(raw: string | undefined): RelayUrlCheck {
  if (!raw) return { ok: false, reason: 'MISSING' };
  if (!URL.canParse(raw)) return { ok: false, reason: 'INVALID' };
  const url = new URL(raw);
  if (url.username || url.password || url.search || url.hash) return { ok: false, reason: 'INVALID' };
  if (url.protocol === 'https:') return { ok: true, url: url.origin };
  if (url.protocol === 'http:' && isLoopbackHost(url.hostname)) return { ok: true, url: url.origin };
  return { ok: false, reason: url.protocol === 'http:' ? 'CLEARTEXT_NON_LOOPBACK' : 'INVALID' };
}
