// The browser's standalone validator must agree with the runtime Ajv the relay and tools use, on the shared fixtures.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { test } from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';

const url = (path) => new URL(path, import.meta.url);
const schemaText = readFileSync(url('../../../../packages/contracts/schema.json'), 'utf8');
const schema = JSON.parse(schemaText);
const fixtures = JSON.parse(readFileSync(url('../../../../packages/contracts/fixtures.json'), 'utf8'));
const generated = await import('../../shared/contracts/item-draft-validator.generated.js');

const ajv = new Ajv2020({ strict: false, allErrors: true });
ajv.addSchema(schema);
const runtime = ajv.getSchema(`${schema.$id}#/$defs/ItemDraft`);

test('generated validator embeds the current schema hash', () => {
  assert.equal(generated.SCHEMA_SHA256, createHash('sha256').update(schemaText).digest('hex'));
});

test('standalone and runtime validators agree on every shared fixture inbound draft', () => {
  let compared = 0;
  for (const fixture of fixtures) {
    const value = fixture.value ?? null;
    if (!value || typeof value !== 'object' || !value.inbound) continue;
    assert.equal(generated.default(value.inbound), runtime(value.inbound), fixture.name);
    compared += 1;
  }
  assert.ok(compared > 20, `only ${compared} fixtures compared`);
});

test('standalone validator accepts a valid draft and rejects targeted invalid ones like the runtime', () => {
  const valid = fixtures.find((fixture) => fixture.value?.inbound && fixture.valid).value.inbound;
  assert.equal(generated.default(valid), true);
  const mutate = (fn) => { const copy = structuredClone(valid); fn(copy); return copy; };
  const invalid = [
    mutate((d) => { d.payload.name = 'x'.repeat(121); }),
    mutate((d) => { d.payload.measuredDimensionsMm.x = 0; }),
    mutate((d) => { d.payload.measuredDimensionsMm.x = 10001; }),
    mutate((d) => { d.payload.errorMm = 1001; }),
    mutate((d) => { d.extra = true; }),
    mutate((d) => { d.draftId = randomUUID().toUpperCase(); }),
    mutate((d) => { delete d.clientRevision; }),
  ];
  for (const draft of invalid) {
    assert.equal(generated.default(draft), false);
    assert.equal(runtime(draft), false);
  }
  const astral = mutate((d) => { d.payload.name = '🧳'.repeat(120); });
  assert.equal(generated.default(astral), true, '120 code points');
  assert.equal(runtime(astral), true);
});
