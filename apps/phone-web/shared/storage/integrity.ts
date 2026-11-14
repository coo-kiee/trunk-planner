// The persistence boundary: browser storage is outside input. A record is used only if its whole structure is what the app wrote.
// Anything else is kept and reported, never rendered, sent or overwritten.
import type { ItemEditor } from '../../entities/item/model.ts';
import { SHAPE_OPTIONS, ROTATION_OPTIONS, SOURCE_OPTIONS } from '../../entities/item/model.ts';
import type { ConfirmedItem, OutboxEntry, OutboxStatus } from '../../entities/outbox/model.ts';
import type { PairingRecord } from '../../entities/pairing/model.ts';
import { isValidItemDraft } from '../contracts/item-draft.ts';

type Dict = Record<string, unknown>;
const isDict = (value: unknown): value is Dict => typeof value === 'object' && value !== null && !Array.isArray(value);
const isString = (value: unknown): value is string => typeof value === 'string';
const isBool = (value: unknown): value is boolean => typeof value === 'boolean';
const isInt = (value: unknown): value is number => Number.isSafeInteger(value);
const isTime = (value: unknown): value is string => isString(value) && !Number.isNaN(Date.parse(value));
const nullOr = (check: (value: unknown) => boolean) => (value: unknown): boolean => value === null || check(value);

const STATUSES: ReadonlySet<string> = new Set<OutboxStatus>(['pending', 'sending', 'received', 'conflict', 'accepted', 'rejected', 'superseded', 'expired', 'revoked', 'unknown', 'blocked']);
const REJECT_CODES: ReadonlySet<unknown> = new Set(['USER_DISCARDED', 'USER_KEPT_VEHICLE_VERSION', 'VALIDATION_FAILED']);

const isReceipt = (value: unknown) => isDict(value) && isString(value.pairingId) && isString(value.payloadHash) && isTime(value.receivedAt) && isTime(value.expiresAt);
const isConflict = (value: unknown) => isDict(value) && isString(value.authoritativeItemId) && isInt(value.authoritativeRevision);
const isFailure = (value: unknown) => isDict(value) && isString(value.kind) && isString(value.code) && isTime(value.at) && (value.details === undefined || isDict(value.details));
const isConsent = (value: unknown) => isDict(value) && isString(value.consentVersion) && value.dimensionsConsent === true && isBool(value.photoConsent);

function isTerminalAck(value: unknown): boolean {
  if (!isDict(value)) return false;
  if (value.result === 'VEHICLE_ACCEPTED') return isString(value.acceptedItemId) && isInt(value.acceptedRevision);
  return value.result === 'VEHICLE_REJECTED' && REJECT_CODES.has(value.rejectCode);
}

export function isOutboxEntry(value: unknown): value is OutboxEntry {
  if (!isDict(value) || !isString(value.status) || !STATUSES.has(value.status)) return false;
  if (!isValidItemDraft(value.draft)) return false; // The same shared schema the relay and the vehicle enforce.
  return value.key === `${value.draft.draftId}:${value.draft.clientRevision}`
    && isConsent(value.consent)
    && nullOr(isString)(value.photoId)
    && isTime(value.createdAt) && isTime(value.updatedAt)
    && nullOr(isReceipt)(value.receipt)
    && nullOr(isTerminalAck)(value.terminalAck)
    && nullOr(isConflict)(value.conflict)
    && nullOr(isFailure)(value.lastFailure)
    && isInt(value.attempts)
    && nullOr(isTime)(value.nextAttemptAt)
    && nullOr(isInt)(value.replacedBy)
    && isBool(value.resentAfterUnknown)
    // A decided entry must carry the decision that the screens read from it.
    && (value.status !== 'accepted' || (value.terminalAck as Dict | null)?.result === 'VEHICLE_ACCEPTED')
    && (value.status !== 'rejected' || (value.terminalAck as Dict | null)?.result === 'VEHICLE_REJECTED')
    && (value.status !== 'conflict' || value.conflict !== null);
}

export function isPairingRecord(value: unknown): value is PairingRecord {
  return isDict(value) && isString(value.pairingId) && isString(value.phoneToken) && isTime(value.credentialExpiresAt)
    && Array.isArray(value.consentVersions) && value.consentVersions.every(isString)
    && isInt(value.relayConfigVersion) && isTime(value.claimedAt)
    && (value.state === 'ACTIVE' || value.state === 'REVOKED' || value.state === 'EXPIRED');
}

export function isConfirmedItem(value: unknown): value is ConfirmedItem {
  return isDict(value) && isString(value.sentItemId) && isString(value.acceptedItemId) && isInt(value.acceptedRevision) && isString(value.draftKey) && isTime(value.confirmedAt);
}

const oneOfOrEmpty = (options: ReadonlyArray<{ value: string }>) => (value: unknown) => value === '' || options.some((option) => option.value === value);
const isShape = oneOfOrEmpty(SHAPE_OPTIONS);
const isRotation = oneOfOrEmpty(ROTATION_OPTIONS);
const isSource = oneOfOrEmpty(SOURCE_OPTIONS);

function isItemEditor(value: unknown): value is ItemEditor {
  if (!isDict(value)) return false;
  const texts = ['itemId', 'name', 'dimX', 'dimY', 'dimZ', 'legX', 'footZ', 'errorMm', 'removalPriority'];
  return texts.every((field) => isString(value[field])) && isTime(value.createdAt)
    && isShape(value.shapeKind) && isRotation(value.rotationRule) && isSource(value.measurementSource)
    && (value.stackable === null || isBool(value.stackable))
    && isBool(value.confirmedDimensions) && isBool(value.dimensionsConsent) && isBool(value.photoConsent)
    && nullOr(isString)(value.photoId)
    && nullOr((base) => isDict(base) && isString(base.itemId) && isInt(base.revision))(value.base)
    && nullOr((next) => isDict(next) && isString(next.draftId) && isInt(next.clientRevision))(value.continues);
}

export function isStoredEditor(value: unknown): value is { saveId: string; state: ItemEditor } {
  return isDict(value) && isString(value.saveId) && isItemEditor(value.state);
}

export function isStoredPhoto(value: unknown): boolean {
  return isDict(value) && typeof Blob !== 'undefined' && value.blob instanceof Blob && isString(value.id) && isString(value.mime) && /^[0-9a-f]{64}$/.test(String(value.sha256));
}
