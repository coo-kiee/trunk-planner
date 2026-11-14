// Browser-side contract check, against the same schema the relay and the vehicle use.
import type { ItemDraft } from '@cargo/contracts';
import validateGenerated from './item-draft-validator.generated.js';

export function isValidItemDraft(value: unknown): value is ItemDraft {
  return (validateGenerated as (candidate: unknown) => boolean)(value);
}
