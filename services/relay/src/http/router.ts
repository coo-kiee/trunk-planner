// Route table. Phone and vehicle live in separate namespaces; there is deliberately no scene or vehicle-control route.
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { RelayConfig, RelayEnv } from '../config.ts';
import type { Db } from '../db/pool.ts';

export interface RouteContext {
  db: Db;
  config: RelayConfig;
  env: RelayEnv;
  req: IncomingMessage;
  res: ServerResponse;
  params: string[];
  query: URLSearchParams;
  bearer: string | null;
}
export interface JsonResult { status: number; body: unknown }
export type Handler = (ctx: RouteContext) => Promise<JsonResult | void>;

export type RateClass = keyof RelayConfig['rateLimit'];
export interface Route {
  method: 'GET' | 'POST';
  template: string;
  pattern: RegExp;
  bearer: boolean;
  rate: RateClass;
  allowedQuery: string[];
  handler: Handler;
}

const UUID = '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})';
const REV = '([1-9][0-9]{0,15})';

export function compileTemplate(template: string): RegExp {
  const source = template.replaceAll(':draftId', UUID).replaceAll(':clientRevision', REV);
  return new RegExp(`^${source}$`);
}

export function matchRoute(routes: Route[], method: string, path: string): { route: Route; params: string[] } | 'method' | null {
  let pathMatched = false;
  for (const route of routes) {
    const match = route.pattern.exec(path);
    if (!match) continue;
    pathMatched = true;
    if (route.method === method) return { route, params: match.slice(1) };
  }
  return pathMatched ? 'method' : null;
}
