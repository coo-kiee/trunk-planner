// Pure item editing. There are no default real dimensions and a photo never fills or confirms them.
import type { BoxProxy, DimensionsMm, ItemVersion, MeasurementSource, RotationRule, ShapeKind } from '@cargo/contracts';

export const SHAPE_OPTIONS: ReadonlyArray<{ value: ShapeKind; label: string; hint: string }> = [
  { value: 'BOX', label: '상자', hint: '네모난 상자 하나로 계산합니다.' },
  { value: 'L_SHAPE', label: 'ㄱ자', hint: '실측한 두 부분의 상자로 계산합니다.' },
  { value: 'CYLINDER_BOUNDING_BOX', label: '원통(바깥 상자)', hint: '원통을 감싸는 상자로 보수적으로 계산합니다.' },
];
export const ROTATION_OPTIONS: ReadonlyArray<{ value: RotationRule; label: string }> = [
  { value: 'ANY24', label: '어느 방향으로든 돌려도 됨' },
  { value: 'KEEP_UPRIGHT', label: '세워 두어야 함(위아래 유지)' },
  { value: 'FLAT_ONLY', label: '눕혀 두어야 함' },
];
export const SOURCE_OPTIONS: ReadonlyArray<{ value: MeasurementSource; label: string }> = [
  { value: 'USER_TAPE', label: '줄자로 직접 잼' },
  { value: 'REPEATED_TAPE', label: '줄자로 여러 번 재서 확인함' },
  { value: 'PUBLISHED_PARTIAL', label: '제조사 공개 치수(일부)' },
  { value: 'DEMO_SYNTHETIC', label: '시연용 가상 값(실물 아님)' },
];

export interface ItemEditor {
  /** Local editing identity, stable across reloads. */
  itemId: string;
  createdAt: string;
  name: string;
  shapeKind: ShapeKind | '';
  dimX: string;
  dimY: string;
  dimZ: string;
  legX: string;
  footZ: string;
  errorMm: string;
  measurementSource: MeasurementSource | '';
  rotationRule: RotationRule | '';
  stackable: boolean | null;
  removalPriority: string;
  confirmedDimensions: boolean;
  dimensionsConsent: boolean;
  photoId: string | null;
  photoConsent: boolean;
  /** Set when editing an item the vehicle already holds: its real id and revision, never a guess. */
  base: { itemId: string; revision: number } | null;
  /** Set when this edit replaces an earlier outbox revision of the same draft. */
  continues: { draftId: string; clientRevision: number } | null;
}

export function newEditor(itemId: string, nowIso: string): ItemEditor {
  return {
    itemId, createdAt: nowIso, name: '', shapeKind: '', dimX: '', dimY: '', dimZ: '', legX: '', footZ: '', errorMm: '',
    measurementSource: '', rotationRule: '', stackable: null, removalPriority: '',
    confirmedDimensions: false, dimensionsConsent: false, photoId: null, photoConsent: false, base: null, continues: null,
  };
}

/** Anything that changes the measured size or its proxy revokes the user's confirmation. */
const REVOKES_CONFIRMATION = new Set<keyof ItemEditor>(['shapeKind', 'dimX', 'dimY', 'dimZ', 'legX', 'footZ', 'errorMm', 'measurementSource']);

export function editItem<K extends keyof ItemEditor>(state: ItemEditor, field: K, value: ItemEditor[K]): ItemEditor {
  const next = { ...state, [field]: value };
  if (REVOKES_CONFIRMATION.has(field) && state[field] !== value) next.confirmedDimensions = false;
  return next;
}

export type EditorField = 'name' | 'shapeKind' | 'dimX' | 'dimY' | 'dimZ' | 'legX' | 'footZ' | 'errorMm' | 'measurementSource' | 'rotationRule' | 'stackable' | 'removalPriority' | 'confirmedDimensions' | 'dimensionsConsent';
export type EditorErrors = Partial<Record<EditorField, string>>;

/** Strict decimal integer: no sign, exponent, spaces or leading zero slip through into millimetres. */
function parseInteger(text: string, min: number, max: number): number | null {
  if (!/^(0|[1-9][0-9]*)$/.test(text)) return null;
  const value = Number(text);
  return value >= min && value <= max ? value : null;
}

/** The schema's maxLength counts Unicode code points, as does this. */
export const nameLength = (name: string): number => [...name].length;
export const NAME_MAX = 120;

const DIM_MESSAGE = '1~10000 사이의 mm 정수로 입력하세요.';

export function validateShapeStep(state: ItemEditor): EditorErrors {
  const errors: EditorErrors = {};
  const name = state.name.trim();
  if (name.length === 0) errors.name = '물품 이름을 입력하세요.';
  else if (nameLength(name) > NAME_MAX) errors.name = `이름은 ${NAME_MAX}자까지 입력할 수 있습니다. 지금 ${nameLength(name)}자입니다.`;
  if (state.shapeKind === '') errors.shapeKind = '기본 형상을 고르세요.';
  return errors;
}

export function validateMeasureStep(state: ItemEditor): EditorErrors {
  const errors: EditorErrors = {};
  const x = parseInteger(state.dimX, 1, 10000);
  const y = parseInteger(state.dimY, 1, 10000);
  const z = parseInteger(state.dimZ, 1, 10000);
  if (x === null) errors.dimX = DIM_MESSAGE;
  if (y === null) errors.dimY = DIM_MESSAGE;
  if (z === null) errors.dimZ = DIM_MESSAGE;
  if (state.shapeKind === 'L_SHAPE') {
    const leg = parseInteger(state.legX, 1, 10000);
    const foot = parseInteger(state.footZ, 1, 10000);
    if (leg === null || (x !== null && leg >= x)) errors.legX = '기둥 폭은 1mm 이상이고 전체 폭(x)보다 작아야 합니다.';
    if (foot === null || (z !== null && foot >= z)) errors.footZ = '발 깊이는 1mm 이상이고 전체 깊이(z)보다 작아야 합니다.';
  }
  if (parseInteger(state.errorMm, 0, 1000) === null) errors.errorMm = '오차는 0~1000 사이의 mm 정수로 입력하세요.';
  if (state.measurementSource === '') errors.measurementSource = '측정 출처를 고르세요.';
  if (state.rotationRule === '') errors.rotationRule = '회전·세움 조건을 고르세요.';
  if (state.stackable === null) errors.stackable = '위에 다른 짐을 올려도 되는지 고르세요.';
  if (parseInteger(state.removalPriority, 0, 100) === null) errors.removalPriority = '꺼냄 우선순위는 0~100 사이의 정수입니다. 작을수록 먼저 꺼냅니다.';
  return errors;
}

export function validateForEnqueue(state: ItemEditor): EditorErrors {
  const errors = { ...validateShapeStep(state), ...validateMeasureStep(state) };
  if (!state.confirmedDimensions) errors.confirmedDimensions = '직접 잰 치수가 맞는지 확정해야 보낼 수 있습니다.';
  if (!state.dimensionsConsent) errors.dimensionsConsent = '치수·취급조건 전송에 동의해야 보낼 수 있습니다.';
  return errors;
}

/**
 * Serialises the user's measurements into the shared proxy shape. This builds data only: no fit, collision or path decision.
 * L_SHAPE follows the vehicle fixture: a full-depth leg of width legX plus a foot of depth footZ, sharing the bounding box.
 */
export function buildShapeProxy(kind: ShapeKind, size: DimensionsMm, legX: number, footZ: number): BoxProxy[] {
  const origin = { x: 0, y: 0, z: 0 };
  if (kind !== 'L_SHAPE') return [{ origin, size }];
  return [
    { origin, size: { x: legX, y: size.y, z: size.z } },
    { origin: { x: legX, y: 0, z: 0 }, size: { x: size.x - legX, y: size.y, z: footZ } },
  ];
}

export type BuildResult = { ok: true; item: ItemVersion } | { ok: false; errors: EditorErrors };

/** The single enqueue boundary: unconfirmed dimensions cannot become a transferable item, whatever the UI did. */
export function buildItemVersion(state: ItemEditor, photoRef: string | null, nowIso: string): BuildResult {
  const errors = validateForEnqueue(state);
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  const size = { x: Number(state.dimX), y: Number(state.dimY), z: Number(state.dimZ) };
  const shapeKind = state.shapeKind as ShapeKind;
  const item: ItemVersion = {
    schemaVersion: 1,
    id: state.base?.itemId ?? state.itemId,
    // A proposal for the vehicle to judge. The vehicle's own acceptedRevision is the only confirmed number.
    revision: state.base ? state.base.revision + 1 : 1,
    createdAt: state.createdAt,
    updatedAt: nowIso,
    name: state.name.trim(),
    ...(photoRef ? { photoRef } : {}),
    shapeKind,
    shapeProxy: buildShapeProxy(shapeKind, size, Number(state.legX), Number(state.footZ)),
    measuredDimensionsMm: size,
    errorMm: Number(state.errorMm),
    measurementSource: state.measurementSource as MeasurementSource,
    rotationRule: state.rotationRule as RotationRule,
    stackable: state.stackable as boolean,
    removalPriority: Number(state.removalPriority),
    confirmedDimensions: true,
  };
  return { ok: true, item };
}

/** Rebuilds an editable state from a sent payload. The user must confirm dimensions again before anything is resent. */
export function editorFromItem(item: ItemVersion, draftKey: { draftId: string; clientRevision: number } | null, base: ItemEditor['base'], photoId: string | null, photoConsent: boolean): ItemEditor {
  const leg = item.shapeProxy[0]?.size.x;
  const foot = item.shapeProxy[1]?.size.z;
  return {
    itemId: item.id,
    createdAt: item.createdAt,
    name: item.name,
    shapeKind: item.shapeKind,
    dimX: String(item.measuredDimensionsMm.x),
    dimY: String(item.measuredDimensionsMm.y),
    dimZ: String(item.measuredDimensionsMm.z),
    legX: item.shapeKind === 'L_SHAPE' && leg !== undefined ? String(leg) : '',
    footZ: item.shapeKind === 'L_SHAPE' && foot !== undefined ? String(foot) : '',
    errorMm: String(item.errorMm),
    measurementSource: item.measurementSource,
    rotationRule: item.rotationRule,
    stackable: item.stackable,
    removalPriority: String(item.removalPriority),
    confirmedDimensions: false,
    dimensionsConsent: false,
    photoId,
    photoConsent,
    base,
    continues: draftKey,
  };
}
