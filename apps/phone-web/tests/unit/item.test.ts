import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildItemVersion, buildShapeProxy, editItem, editorFromItem, nameLength, newEditor, validateForEnqueue, validateShapeStep, type ItemEditor } from '../../entities/item/model.ts';
import { sampleItem } from './samples.ts';

const filled = (): ItemEditor => ({
  ...newEditor('12345678-1234-4234-8234-123456789abc', '2026-11-04T00:00:00.000Z'),
  name: '상자', shapeKind: 'BOX', dimX: '400', dimY: '300', dimZ: '200', errorMm: '5', measurementSource: 'USER_TAPE',
  rotationRule: 'ANY24', stackable: true, removalPriority: '0', confirmedDimensions: true, dimensionsConsent: true,
});

test('a new item has no real-dimension defaults and is not confirmed', () => {
  const blank = newEditor('id', 'now');
  assert.deepEqual([blank.dimX, blank.dimY, blank.dimZ, blank.errorMm], ['', '', '', '']);
  assert.equal(blank.confirmedDimensions, false);
});

test('the enqueue boundary refuses unconfirmed dimensions regardless of the UI', () => {
  const result = buildItemVersion({ ...filled(), confirmedDimensions: false }, null, 'now');
  assert.equal(result.ok, false);
  assert.ok(!result.ok && result.errors.confirmedDimensions);
});

test('dimension, proxy, error and source edits revoke confirmation; unrelated ones do not', () => {
  for (const [field, value] of [['dimX', '401'], ['dimY', '301'], ['dimZ', '201'], ['legX', '9'], ['footZ', '9'], ['shapeKind', 'L_SHAPE'], ['errorMm', '6'], ['measurementSource', 'REPEATED_TAPE']] as const) {
    assert.equal(editItem(filled(), field, value as never).confirmedDimensions, false, field);
  }
  assert.equal(editItem(filled(), 'name', '새 이름').confirmedDimensions, true);
  assert.equal(editItem(filled(), 'dimX', '400').confirmedDimensions, true, 'same value is not a change');
});

test('a photo changes nothing about the dimensions and cannot confirm them', () => {
  const withPhoto = editItem(editItem(newEditor('id', 'now'), 'photoId', 'photo-1'), 'photoConsent', true);
  assert.deepEqual([withPhoto.dimX, withPhoto.dimY, withPhoto.dimZ], ['', '', '']);
  assert.equal(withPhoto.confirmedDimensions, false);
});

test('integer boundaries are strict: 1 and 10000 pass, 0, 10001, signs, decimals and exponents fail', () => {
  for (const good of ['1', '10000']) assert.equal(validateForEnqueue({ ...filled(), dimX: good }).dimX, undefined, good);
  for (const bad of ['0', '10001', '-1', '+1', '1.5', '1e3', ' 1', '01', '']) assert.ok(validateForEnqueue({ ...filled(), dimX: bad }).dimX, bad);
  assert.equal(validateForEnqueue({ ...filled(), errorMm: '0' }).errorMm, undefined);
  assert.ok(validateForEnqueue({ ...filled(), errorMm: '1001' }).errorMm);
  assert.equal(validateForEnqueue({ ...filled(), removalPriority: '100' }).removalPriority, undefined);
  assert.ok(validateForEnqueue({ ...filled(), removalPriority: '101' }).removalPriority);
});

test('name length is counted in Unicode code points: 120 passes, 121 fails', () => {
  const astral = '🧳';
  assert.equal(nameLength(astral), 1);
  assert.equal(validateShapeStep({ ...filled(), name: astral.repeat(120) }).name, undefined);
  assert.ok(validateShapeStep({ ...filled(), name: astral.repeat(121) }).name);
  assert.ok(validateShapeStep({ ...filled(), name: '   ' }).name);
});

test('L proxy is two positive boxes whose bounding box equals the measurement, as the vehicle core requires', () => {
  const size = { x: 300, y: 100, z: 300 };
  const boxes = buildShapeProxy('L_SHAPE', size, 100, 100);
  assert.deepEqual(boxes, [
    { origin: { x: 0, y: 0, z: 0 }, size: { x: 100, y: 100, z: 300 } },
    { origin: { x: 100, y: 0, z: 0 }, size: { x: 200, y: 100, z: 100 } },
  ]);
  const maxX = Math.max(...boxes.map((box) => box.origin.x + box.size.x));
  const maxZ = Math.max(...boxes.map((box) => box.origin.z + box.size.z));
  assert.deepEqual([maxX, maxZ], [300, 300]);
});

test('L measurements outside the bounding box are field errors', () => {
  const l = { ...filled(), shapeKind: 'L_SHAPE' as const, legX: '400', footZ: '10' };
  assert.ok(validateForEnqueue(l).legX);
  assert.ok(validateForEnqueue({ ...l, legX: '10', footZ: '200' }).footZ);
  assert.deepEqual(validateForEnqueue({ ...l, legX: '10', footZ: '10' }), {});
});

test('a shared-item edit sends the base and proposes base+1, without claiming it', () => {
  const result = buildItemVersion({ ...filled(), base: { itemId: 'vehicle-item', revision: 7 } }, null, '2026-11-04T01:00:00.000Z');
  assert.ok(result.ok);
  assert.equal(result.item.id, 'vehicle-item');
  assert.equal(result.item.revision, 8);
});

test('reopening a sent payload needs the user to confirm and consent again', () => {
  const reopened = editorFromItem(sampleItem(), { draftId: 'd', clientRevision: 1 }, null, null, false);
  assert.equal(reopened.confirmedDimensions, false);
  assert.equal(reopened.dimensionsConsent, false);
  assert.equal(reopened.dimX, '400');
});
