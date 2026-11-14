// Phone namespace: claim once, submit item drafts, read back status. A phone can never read another draft's content.
import { claimPairing, revokePairing } from '../db/pairings.ts';
import { readDraftStatus, submitDraft } from '../db/drafts.ts';
import { assertCredentialActive } from '../db/pool.ts';
import { RelayError } from '../domain/errors.ts';
import { parseSubmission } from '../domain/submission.ts';
import { isTokenShaped } from '../domain/tokens.ts';
import { readJsonBody } from '../http/body.ts';
import { compileTemplate, type Route } from '../http/router.ts';
import type { RelayConfig } from '../config.ts';

/** Base64 expansion of the largest photo plus the JSON part of the envelope. */
export function draftBodyCap(config: RelayConfig): number {
  return Math.ceil(config.limits.photoDecodedBytes / 3) * 4 + config.limits.draftJsonBodyBytes + 256;
}

export const phoneRoutes: Route[] = [
  {
    method: 'POST',
    template: '/phone/v1/pairing/claim',
    pattern: compileTemplate('/phone/v1/pairing/claim'),
    bearer: false,
    rate: 'claim',
    allowedQuery: [],
    handler: async ({ db, req, config }) => {
      const { value } = await readJsonBody(req, config.limits.claimBodyBytes);
      const record = value as { pairingToken?: unknown } | null;
      const keys = record && typeof record === 'object' ? Object.keys(record) : [];
      const token = record?.pairingToken;
      if (keys.length !== 1 || typeof token !== 'string' || !isTokenShaped(token)) throw new RelayError(401, 'UNAUTHENTICATED');
      const claimed = await claimPairing(db, token);
      return {
        status: 200,
        body: {
          pairingId: claimed.pairingId,
          phoneToken: claimed.phoneToken,
          credentialExpiresAt: claimed.credentialExpiresAt.toISOString(),
          consentVersions: config.consentVersions,
          relayConfigVersion: config.relayConfigVersion,
        },
      };
    },
  },
  {
    method: 'POST',
    template: '/phone/v1/pairing/revoke',
    pattern: compileTemplate('/phone/v1/pairing/revoke'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => ({ status: 200, body: { revoked: true, ...(await revokePairing(ctx.db, ctx.bearer!, 'PHONE')) } }),
  },
  {
    method: 'POST',
    template: '/phone/v1/drafts',
    pattern: compileTemplate('/phone/v1/drafts'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => {
      await assertCredentialActive(ctx.db, ctx.bearer!, ['PHONE']);
      const { value, bytes } = await readJsonBody(ctx.req, draftBodyCap(ctx.config));
      const submission = parseSubmission(value, bytes, ctx.config);
      const receipt = await submitDraft(ctx.db, ctx.bearer!, submission, ctx.config);
      return { status: receipt.duplicate ? 200 : 201, body: receipt };
    },
  },
  {
    method: 'GET',
    template: '/phone/v1/drafts/:draftId/:clientRevision',
    pattern: compileTemplate('/phone/v1/drafts/:draftId/:clientRevision'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => ({ status: 200, body: await readDraftStatus(ctx.db, ctx.bearer!, ctx.params[0]!, Number(ctx.params[1])) }),
  },
];
