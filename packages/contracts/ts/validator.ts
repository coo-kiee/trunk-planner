import Ajv2020 from 'ajv/dist/2020';
import schema from '../schema.json';
import type { Envelope } from './contracts';

const ajv = new Ajv2020({strict: false, allErrors: true});
export const validate = ajv.compile<Envelope>(schema);
export function decode(text: string): Envelope {
  const value: unknown = JSON.parse(text);
  if (!validate(value)) throw new Error(ajv.errorsText(validate.errors));
  return value;
}
export function encode(value: Envelope): string {
  if (!validate(value)) throw new Error(ajv.errorsText(validate.errors));
  return JSON.stringify(value);
}

/** The same trust boundary is available for individual inbound/storage DTOs. */
export function decodeDefinition(name: keyof typeof schema.$defs, text: string): unknown {
  const value: unknown = JSON.parse(text);
  const check = ajv.getSchema(`${schema.$id}#/$defs/${name}`);
  if (!check || !check(value)) throw new Error(`Invalid ${name}: ${ajv.errorsText(check?.errors)}`);
  return value;
}
