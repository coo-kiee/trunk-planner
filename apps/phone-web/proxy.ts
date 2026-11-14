// Every document gets a fresh script nonce and a closed CSP. worker-src 'none' makes the "no service worker" rule enforceable.
import { NextResponse, type NextRequest } from 'next/server';
import { readRelayBaseUrl } from './shared/config/relay-url.ts';

function contentSecurityPolicy(nonce: string, relayOrigin: string | null): string {
  return [
    "default-src 'none'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`,
    "style-src 'self'",
    "img-src 'self' blob:",
    `connect-src 'self'${relayOrigin ? ` ${relayOrigin}` : ''}`,
    "font-src 'none'",
    "manifest-src 'none'",
    "worker-src 'none'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join('; ');
}

export function proxy(request: NextRequest): NextResponse {
  const nonce = btoa(crypto.randomUUID());
  const relay = readRelayBaseUrl(process.env.PHONE_RELAY_BASE_URL);
  const csp = contentSecurityPolicy(nonce, relay.ok ? new URL(relay.url).origin : null);

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('x-nonce', nonce);
  requestHeaders.set('content-security-policy', csp); // Next reads the nonce for its own scripts from this header.

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set('Content-Security-Policy', csp);
  response.headers.set('Referrer-Policy', 'no-referrer');
  response.headers.set('Cache-Control', 'no-store');
  response.headers.set('X-Content-Type-Options', 'nosniff');
  return response;
}

export const config = {
  matcher: [{ source: '/((?!_next/static|_next/image|favicon.ico).*)' }],
};
