// Shared paths and evidence writing for the phone-web tools.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const repoRoot = resolve(appRoot, '../..');
export const evidenceDir = join(repoRoot, 'evidence/product/phone-web');

export function writeEvidence(name, value) {
  mkdirSync(dirname(join(evidenceDir, name)), { recursive: true });
  writeFileSync(join(evidenceDir, name), `${JSON.stringify(value, null, 2)}\n`);
}
