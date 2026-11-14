// Typed adapter over relay protocol v1, phone namespace only. Tokens travel in the Authorization header or the claim body.
import type { ItemDraft } from '@cargo/contracts';
import type { RelayStatusBody, TransferConsent } from '../../entities/outbox/model.ts';
import type { RelayFailure } from '../../entities/outbox/retry.ts';

export type RelayResult<T> = { ok: true; status: number; body: T } | ({ ok: false } & RelayFailure);

export interface ClaimBody {
  pairingId: string;
  phoneToken: string;
  credentialExpiresAt: string;
  consentVersions: string[];
  relayConfigVersion: number;
}
export interface SubmitPayload {
  consent: TransferConsent;
  draft: ItemDraft;
  photo?: { mime: string; dataBase64: string };
}

const REQUEST_TIMEOUT_MS = 30_000;

async function call<T>(baseUrl: string, path: string, init: { method: 'GET' | 'POST'; token?: string; json?: unknown; signal?: AbortSignal }): Promise<RelayResult<T>> {
  const headers: Record<string, string> = {};
  if (init.token) headers.authorization = `Bearer ${init.token}`;
  if (init.json !== undefined) headers['content-type'] = 'application/json';
  const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(baseUrl + path, {
      method: init.method,
      headers,
      ...(init.json === undefined ? {} : { body: JSON.stringify(init.json) }),
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout,
    });
  } catch {
    // Offline, DNS, TLS and a refused CORS origin look identical from here. The caller shows them as one connection failure.
    return { ok: false, kind: 'network' };
  }
  const parsed: unknown = await response.json().catch(() => null);
  if (response.ok) return { ok: true, status: response.status, body: parsed as T };
  const error = (parsed as { error?: Record<string, unknown> } | null)?.error ?? {};
  const { code, requestId: _requestId, ...details } = error;
  return { ok: false, kind: 'http', status: response.status, code: typeof code === 'string' ? code : `HTTP_${response.status}`, details };
}

export const claimPairing = (baseUrl: string, pairingToken: string) =>
  call<ClaimBody>(baseUrl, '/phone/v1/pairing/claim', { method: 'POST', json: { pairingToken } });

export const revokePairing = (baseUrl: string, token: string) =>
  call<{ revoked: true }>(baseUrl, '/phone/v1/pairing/revoke', { method: 'POST', token });

export const submitDraft = (baseUrl: string, token: string, payload: SubmitPayload, signal?: AbortSignal) =>
  call<RelayStatusBody & { duplicate: boolean }>(baseUrl, '/phone/v1/drafts', { method: 'POST', token, json: payload, ...(signal ? { signal } : {}) });

export const readDraftStatus = (baseUrl: string, token: string, draftId: string, clientRevision: number, signal?: AbortSignal) =>
  call<RelayStatusBody>(baseUrl, `/phone/v1/drafts/${draftId}/${clientRevision}`, { method: 'GET', token, ...(signal ? { signal } : {}) });
