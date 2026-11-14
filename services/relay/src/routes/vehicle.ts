// Vehicle namespace: outbound poll only. The relay never initiates a call to the vehicle.
import { createPairing, readPairingStatus, revokePairing } from '../db/pairings.ts';
import { listDraftsForVehicle, readPhotoForVehicle, recordAck } from '../db/drafts.ts';
import { assertCredentialActive } from '../db/pool.ts';
import { parseAck } from '../domain/ack.ts';
import { RelayError } from '../domain/errors.ts';
import { readJsonBody } from '../http/body.ts';
import { compileTemplate, type Route, type RouteContext } from '../http/router.ts';

function bearerOf(ctx: RouteContext): string {
  if (!ctx.bearer) throw new RelayError(401, 'UNAUTHENTICATED');
  return ctx.bearer;
}

function intQuery(query: URLSearchParams, name: string, fallback: number, max: number): number {
  const raw = query.get(name);
  if (raw === null) return fallback;
  if (!/^[0-9]{1,16}$/.test(raw) || Number(raw) > max) throw new RelayError(400, 'QUERY_INVALID');
  return Number(raw);
}

export const vehicleRoutes: Route[] = [
  {
    method: 'POST',
    template: '/vehicle/v1/pairings',
    pattern: compileTemplate('/vehicle/v1/pairings'),
    bearer: false,
    rate: 'createPairing',
    allowedQuery: [],
    handler: async ({ db, config, env }) => {
      const created = await createPairing(db, config);
      // The QR carries only a URL with a fragment token; item data never travels through the QR.
      const qrUrl = `${env.phoneWebBaseUrl.href}#pairing=${created.pairingToken}`;
      return {
        status: 201,
        body: {
          pairingId: created.pairingId,
          vehicleToken: created.vehicleToken,
          qrUrl,
          claimExpiresAt: created.claimExpiresAt.toISOString(),
          credentialExpiresAt: created.credentialExpiresAt.toISOString(),
          vehicleRelayBaseUrl: env.vehicleRelayBaseUrl.href,
          relayConfigVersion: config.relayConfigVersion,
        },
      };
    },
  },
  {
    method: 'GET',
    template: '/vehicle/v1/pairing',
    pattern: compileTemplate('/vehicle/v1/pairing'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => ({ status: 200, body: await readPairingStatus(ctx.db, bearerOf(ctx)) }),
  },
  {
    method: 'POST',
    template: '/vehicle/v1/pairing/revoke',
    pattern: compileTemplate('/vehicle/v1/pairing/revoke'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => ({ status: 200, body: { revoked: true, ...(await revokePairing(ctx.db, bearerOf(ctx), 'VEHICLE')) } }),
  },
  {
    method: 'GET',
    template: '/vehicle/v1/drafts',
    pattern: compileTemplate('/vehicle/v1/drafts'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: ['after', 'limit'],
    handler: async (ctx) => {
      const max = ctx.config.limits.listPageMax;
      const after = intQuery(ctx.query, 'after', 0, Number.MAX_SAFE_INTEGER);
      const limit = intQuery(ctx.query, 'limit', max, max);
      return { status: 200, body: await listDraftsForVehicle(ctx.db, bearerOf(ctx), after, Math.max(limit, 1)) };
    },
  },
  {
    method: 'GET',
    template: '/vehicle/v1/drafts/:draftId/:clientRevision/photo',
    pattern: compileTemplate('/vehicle/v1/drafts/:draftId/:clientRevision/photo'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => {
      const photo = await readPhotoForVehicle(ctx.db, bearerOf(ctx), ctx.params[0]!, Number(ctx.params[1]));
      ctx.res.writeHead(200, {
        'Content-Type': photo.mime,
        'Content-Length': photo.bytes.length,
        'Content-Disposition': 'attachment',
        'X-Photo-Sha256': photo.sha256,
      });
      ctx.res.end(photo.bytes);
    },
  },
  {
    method: 'POST',
    template: '/vehicle/v1/drafts/:draftId/:clientRevision/ack',
    pattern: compileTemplate('/vehicle/v1/drafts/:draftId/:clientRevision/ack'),
    bearer: true,
    rate: 'authenticated',
    allowedQuery: [],
    handler: async (ctx) => {
      await assertCredentialActive(ctx.db, bearerOf(ctx), ['VEHICLE']);
      const { value } = await readJsonBody(ctx.req, ctx.config.limits.ackBodyBytes);
      const result = await recordAck(ctx.db, bearerOf(ctx), ctx.params[0]!, Number(ctx.params[1]), parseAck(value));
      return { status: 200, body: result };
    },
  },
];
