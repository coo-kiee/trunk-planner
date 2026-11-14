// Validates the phone transfer envelope (relay protocol v1) around the shared ItemDraft contract.
import { createRequire } from 'node:module';
import type { RelayConfig } from '../config.ts';
import { RelayError } from './errors.ts';
import { canonicalHash, sha256Hex } from './hash.ts';
import { inspectPhoto, type PhotoInfo } from './photo-policy.ts';

type ContractsModule = { decodeDefinition: (name: 'ItemDraft', text: string) => unknown };

/** Resolved from the workspace; a missing build must fail loudly instead of skipping contract validation. */
function loadContracts(): ContractsModule {
  const require = createRequire(import.meta.url);
  try {
    return require('@cargo/contracts');
  } catch (cause) {
    throw new Error('@cargo/contracts is not built: run `npm run build --workspace=@cargo/contracts` at the repository root', { cause });
  }
}
const contracts = loadContracts();

export interface ItemDraftWire {
  draftId: string;
  pairingId: string;
  baseItemId?: string;
  baseItemRevision?: number;
  schemaVersion: 1;
  clientRevision: number;
  payload: { photoRef?: string } & Record<string, unknown>;
}
export interface ValidSubmission {
  draft: ItemDraftWire;
  consent: { consentVersion: string; dimensionsConsent: true; photoConsent: boolean };
  photo: (PhotoInfo & { mime: string; bytes: Buffer; sha256: string }) | null;
  payloadSha256: string;
  payloadBytes: number;
}

const STRICT_BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function asRecord(value: unknown, code: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new RelayError(400, code);
  return value as Record<string, unknown>;
}

function rejectUnknownKeys(value: Record<string, unknown>, allowed: string[], code: string): void {
  const unknown = Object.keys(value).filter((key) => !allowed.includes(key));
  // Field names are client-controlled text: report only that something was unknown.
  if (unknown.length > 0) throw new RelayError(400, code);
}

function parseConsent(raw: unknown, config: RelayConfig): ValidSubmission['consent'] {
  const consent = asRecord(raw, 'CONSENT_REQUIRED');
  rejectUnknownKeys(consent, ['consentVersion', 'dimensionsConsent', 'photoConsent'], 'CONSENT_INVALID');
  if (typeof consent.consentVersion !== 'string' || !config.consentVersions.includes(consent.consentVersion)) {
    throw new RelayError(400, 'CONSENT_VERSION_UNSUPPORTED', { supported: config.consentVersions });
  }
  if (consent.dimensionsConsent !== true) throw new RelayError(400, 'CONSENT_REQUIRED');
  if (typeof consent.photoConsent !== 'boolean') throw new RelayError(400, 'CONSENT_INVALID');
  return { consentVersion: consent.consentVersion, dimensionsConsent: true, photoConsent: consent.photoConsent };
}

function parsePhoto(raw: unknown, photoConsent: boolean, config: RelayConfig): ValidSubmission['photo'] {
  if (raw === undefined) return null;
  if (!photoConsent) throw new RelayError(400, 'PHOTO_CONSENT_REQUIRED');
  const photo = asRecord(raw, 'PHOTO_MALFORMED');
  // Deliberately no filename field: an original file name would be an unknown key and is refused.
  rejectUnknownKeys(photo, ['mime', 'dataBase64'], 'PHOTO_FIELD_FORBIDDEN');
  if (typeof photo.mime !== 'string' || typeof photo.dataBase64 !== 'string' || !STRICT_BASE64.test(photo.dataBase64)) {
    throw new RelayError(400, 'PHOTO_MALFORMED');
  }
  const bytes = Buffer.from(photo.dataBase64, 'base64');
  const info = inspectPhoto(photo.mime, bytes, config.limits);
  return { ...info, mime: photo.mime, bytes, sha256: sha256Hex(bytes) };
}

function parseDraft(raw: unknown): ItemDraftWire {
  try {
    return contracts.decodeDefinition('ItemDraft', JSON.stringify(raw)) as ItemDraftWire;
  } catch {
    // The validator message can echo client content; only the typed code leaves this function.
    throw new RelayError(422, 'DRAFT_SCHEMA_INVALID');
  }
}

export function parseSubmission(body: unknown, rawBytes: number, config: RelayConfig): ValidSubmission {
  const envelope = asRecord(body, 'ENVELOPE_INVALID');
  rejectUnknownKeys(envelope, ['consent', 'draft', 'photo'], 'ENVELOPE_FIELD_FORBIDDEN');
  const consent = parseConsent(envelope.consent, config);
  const photo = parsePhoto(envelope.photo, consent.photoConsent, config);
  if (!photo && rawBytes > config.limits.draftJsonBodyBytes) {
    throw new RelayError(413, 'BODY_TOO_LARGE', { maxBytes: config.limits.draftJsonBodyBytes });
  }
  const draft = parseDraft(envelope.draft);
  if ((draft.baseItemId === undefined) !== (draft.baseItemRevision === undefined)) {
    throw new RelayError(422, 'DRAFT_BASE_INCOMPLETE');
  }
  // photoRef must not smuggle a file name or public URL; it can only name the attached photo's content hash.
  const expectedRef = photo ? `sha256:${photo.sha256}` : undefined;
  if (draft.payload.photoRef !== expectedRef) throw new RelayError(422, 'PHOTO_REF_INVALID', { expected: photo ? 'sha256:<photo sha256>' : 'absent' });
  const payloadSha256 = canonicalHash({ draft, photoSha256: photo?.sha256 ?? null });
  const payloadBytes = Buffer.byteLength(JSON.stringify(draft)) + (photo?.bytes.length ?? 0);
  return { draft, consent, photo, payloadSha256, payloadBytes };
}
