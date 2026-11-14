// HTTP boundary: routing, bearer extraction, URL-secret rejection, rate limits, typed errors and allowlist logging.
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import type { RelayConfig, RelayEnv } from '../config.ts';
import type { Db } from '../db/pool.ts';
import { RelayError } from '../domain/errors.ts';
import { digestToken, isTokenShaped, TOKEN_IN_TEXT } from '../domain/tokens.ts';
import { log } from '../logger.ts';
import { phoneRoutes } from '../routes/phone.ts';
import { vehicleRoutes } from '../routes/vehicle.ts';
import { RateLimiter } from './rate-limit.ts';
import { matchRoute, type Route } from './router.ts';

export const routes: Route[] = [...vehicleRoutes, ...phoneRoutes];

const BEARER_LIKE_QUERY = /token|bearer|authorization|auth|key|secret/i;
const CONNECTION_ERROR_CODES = new Set(['ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'EPIPE', 'ENOTFOUND']);

/** True when the database is unreachable or shutting down, as opposed to a defect in the request or the service. */
function isDatabaseUnavailable(cause: unknown): boolean {
  const code = (cause as { code?: unknown }).code;
  if (typeof code === 'string' && (CONNECTION_ERROR_CODES.has(code) || code.startsWith('08') || code.startsWith('57P') || code === '53300')) return true;
  // pg-pool and the driver report a lost or never-established connection as plain Errors.
  const message = cause instanceof Error ? cause.message : '';
  return /timeout exceeded when trying to connect|Connection terminated|connection timeout/i.test(message);
}

function assertNoUrlSecret(rawUrl: string): void {
  if (TOKEN_IN_TEXT.test(decodeURIComponentSafe(rawUrl))) throw new RelayError(400, 'URL_BEARER_FORBIDDEN');
}

function decodeURIComponentSafe(text: string): string {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

function parseQuery(url: URL, allowed: string[]): URLSearchParams {
  for (const key of url.searchParams.keys()) {
    if (BEARER_LIKE_QUERY.test(key)) throw new RelayError(400, 'URL_BEARER_FORBIDDEN');
    if (!allowed.includes(key)) throw new RelayError(400, 'QUERY_INVALID');
  }
  return url.searchParams;
}

function readBearer(req: IncomingMessage): string | null {
  const header = req.headers.authorization;
  if (!header) return null;
  const match = /^Bearer (.+)$/.exec(header);
  return match?.[1] && isTokenShaped(match[1]) ? match[1] : null;
}

/** Browsers may call only the phone namespace and only from the configured phone web origin. */
function applyOriginPolicy(req: IncomingMessage, res: ServerResponse, path: string, env: RelayEnv): void {
  const origin = req.headers.origin;
  if (!origin) return;
  if (!path.startsWith('/phone/') || origin !== env.phoneWebBaseUrl.origin) throw new RelayError(403, 'ORIGIN_FORBIDDEN');
  res.setHeader('Access-Control-Allow-Origin', origin);
  res.setHeader('Vary', 'Origin');
}

function setBaselineHeaders(res: ServerResponse, requestId: string, tls: boolean): void {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Request-Id', requestId);
  if (tls) res.setHeader('Strict-Transport-Security', 'max-age=31536000');
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

export function createRelayServer(db: Db, config: RelayConfig, env: RelayEnv): Server {
  const limiter = new RateLimiter();

  const enforceRate = (route: Route, bearer: string | null, address: string): void => {
    const wait = bearer
      ? limiter.take(`t:${digestToken(bearer).toString('base64')}`, config.rateLimit[route.rate])
      : limiter.take(`${route.rate}:${address}`, config.rateLimit[route.rate]);
    if (wait > 0) throw new RelayError(429, 'RATE_LIMITED', {}, wait);
  };

  const dispatch = async (req: IncomingMessage, res: ServerResponse, onRoute: (template: string) => void): Promise<void> => {
    const rawUrl = req.url ?? '/';
    assertNoUrlSecret(rawUrl);
    const url = new URL(rawUrl, 'http://relay.invalid');
    const path = url.pathname;
    const method = req.method ?? 'GET';
    const address = req.socket.remoteAddress ?? 'unknown';

    if (path === '/healthz' && method === 'GET') {
      onRoute('/healthz');
      await db.query('SELECT 1');
      sendJson(res, 200, { status: 'ok', relayConfigVersion: config.relayConfigVersion });
      return;
    }
    applyOriginPolicy(req, res, path, env);
    if (method === 'OPTIONS' && path.startsWith('/phone/')) {
      onRoute('/phone/*:preflight');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST');
      res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
      res.writeHead(204).end();
      return;
    }
    const matched = matchRoute(routes, method, path);
    if (matched === null) throw new RelayError(404, 'NOT_FOUND');
    if (matched === 'method') throw new RelayError(405, 'METHOD_NOT_ALLOWED');
    const { route, params } = matched;
    onRoute(route.template);
    const query = parseQuery(url, route.allowedQuery);

    const bearer = readBearer(req);
    const badAuthKey = `badauth:${address}`;
    if (limiter.exhausted(badAuthKey, config.rateLimit.claim)) throw new RelayError(429, 'RATE_LIMITED', {}, 60);
    if (route.bearer && !bearer) {
      limiter.take(badAuthKey, config.rateLimit.claim);
      throw new RelayError(401, 'UNAUTHENTICATED');
    }
    enforceRate(route, route.bearer ? bearer : null, address);
    try {
      const result = await route.handler({ db, config, env, req, res, params, query, bearer });
      if (result) sendJson(res, result.status, result.body);
    } catch (cause) {
      if (cause instanceof RelayError && cause.code === 'UNAUTHENTICATED') limiter.take(badAuthKey, config.rateLimit.claim);
      throw cause;
    }
  };

  const onRequest = (req: IncomingMessage, res: ServerResponse): void => {
    const started = process.hrtime.bigint();
    const requestId = randomUUID();
    let routeTemplate = 'unmatched';
    setBaselineHeaders(res, requestId, env.tls !== null);
    res.once('finish', () => {
      log('request', {
        requestId, method: req.method, route: routeTemplate, status: res.statusCode,
        durationMs: Number((process.hrtime.bigint() - started) / 1_000_000n),
      });
    });
    dispatch(req, res, (template) => { routeTemplate = template; }).catch((cause: unknown) => {
      if (res.headersSent) {
        res.destroy();
        return;
      }
      respondWithFailure(req, res, cause, requestId);
    });
  };

  const server = env.tls
    ? createHttpsServer({ cert: readFileSync(env.tls.certFile), key: readFileSync(env.tls.keyFile) }, onRequest)
    : createHttpServer(onRequest);
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  // Malformed HTTP never reaches the handler; answer without echoing or logging the raw bytes.
  server.on('clientError', (_cause, socket) => {
    if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
    else socket.destroy();
  });
  return server;
}

function respondWithFailure(req: IncomingMessage, res: ServerResponse, cause: unknown, requestId: string): void {
  let failure: RelayError;
  if (cause instanceof RelayError) {
    failure = cause;
  } else if (isDatabaseUnavailable(cause)) {
    failure = new RelayError(503, 'DATABASE_UNAVAILABLE', {}, 5);
    log('database_unavailable', { requestId, sqlState: pgCode(cause) });
  } else {
    failure = new RelayError(500, 'INTERNAL');
    // Only the error class and SQLSTATE: driver messages can contain values.
    log('internal_error', { requestId, errorClass: (cause as Error)?.constructor?.name ?? 'unknown', sqlState: pgCode(cause) });
  }
  if (failure.retryAfterSeconds !== undefined) res.setHeader('Retry-After', String(failure.retryAfterSeconds));
  if (failure.status === 413) {
    // The body may still be arriving: stop reading it instead of draining it.
    res.setHeader('Connection', 'close');
    res.once('finish', () => req.destroy());
  }
  sendJson(res, failure.status, { error: { code: failure.code, requestId, ...failure.details } });
}

const pgCode = (cause: unknown) => {
  const code = (cause as { code?: unknown } | undefined)?.code;
  return typeof code === 'string' ? code : undefined;
};
