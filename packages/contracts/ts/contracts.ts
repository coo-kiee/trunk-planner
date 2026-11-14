// Generated from schema.json. Do not edit.
export type Uuid = string;
export type Revision = number;
export type Timestamp = string;
export type Hash = string;
export interface Vec3Mm {
  x: number;
  y: number;
  z: number;
}
export interface DimensionsMm {
  x: number;
  y: number;
  z: number;
}
export interface BoxProxy {
  origin: Vec3Mm;
  size: DimensionsMm;
}
export type ShapeKind = "BOX" | "L_SHAPE" | "CYLINDER_BOUNDING_BOX";
export type RotationRule = "ANY24" | "KEEP_UPRIGHT" | "FLAT_ONLY";
export type MeasurementSource = "USER_TAPE" | "REPEATED_TAPE" | "PUBLISHED_PARTIAL" | "DEMO_SYNTHETIC";
export type ProfileGrade = "VERIFIED_MEASURED" | "USER_MEASURED" | "PUBLISHED_PARTIAL" | "DEMO_SYNTHETIC" | "UNSUPPORTED";
export type PlacementStatus = "CLEAR" | "CONFLICT" | "UNCERTAIN";
export type OpeningStatus = "FITS" | "DOES_NOT_FIT" | "UNCERTAIN" | "OPENING_UNMEASURED";
export type HandlingStatus = "OK" | "VIOLATION";
export type SearchStatus = "FOUND_PLACEMENT" | "CERTIFIED_INFEASIBLE_IN_MODEL" | "NO_CANDIDATE_UNCERTIFIED" | "SEARCH_INTERRUPTED" | "NOT_RUN";
export type UncertifiedReason = "PROOF_CLASS_UNSUPPORTED" | "SUPPORT_CONSTRAINT_UNPROVEN" | "MULTI_ITEM_PROOF_UNSUPPORTED";
export interface SearchResult {
  status: SearchStatus;
  reason?: UncertifiedReason;
}
export interface PathResult {
  status: "VERIFIED_IN_MODEL" | "NOT_FOUND_IN_CHECKED_CLASS" | "UNVERIFIED";
  pathClass?: "Z_THEN_XY_AABB_SWEEP_V1";
}
export interface ItemVersion {
  schemaVersion: 1;
  id: Uuid;
  revision: Revision;
  createdAt: Timestamp;
  updatedAt: Timestamp;
  name: string;
  photoRef?: string;
  shapeKind: ShapeKind;
  shapeProxy: Array<BoxProxy>;
  measuredDimensionsMm: DimensionsMm;
  errorMm: number;
  measurementSource: MeasurementSource;
  rotationRule: RotationRule;
  stackable: boolean;
  removalPriority: number;
  confirmedDimensions: boolean;
}
export interface ProfileVersion {
  schemaVersion: 1;
  id: Uuid;
  revision: Revision;
  createdAt: Timestamp;
  updatedAt: Timestamp;
  grade: ProfileGrade;
  bounds?: DimensionsMm;
  obstacles: Array<BoxProxy>;
  opening?: DimensionsMm;
  errorMm: number;
  provenance: string;
  sourceURL?: string;
  measuredAt?: Timestamp;
  method: string;
  repeatMeasurements: Array<DimensionsMm>;
}
export interface Instance {
  instanceId: Uuid;
  itemId: Uuid;
  itemRevision: Revision;
  poseMm: Vec3Mm;
  orientationIndex: number;
  locked: boolean;
}
export interface JudgementSnapshot {
  placement: PlacementStatus;
  opening: OpeningStatus;
  path: PathResult;
  handling: HandlingStatus;
  search: SearchResult;
  minimumClearanceMm: number;
  errorMm: number;
  conflictLocations: Array<Vec3Mm>;
  staleReasons: Array<string>;
  inputHash: Hash;
  safety: "NOT_ASSESSED";
}
export interface SceneRevision {
  schemaVersion: 1;
  id: Uuid;
  revision: Revision;
  createdAt: Timestamp;
  updatedAt: Timestamp;
  parentRevision?: Revision;
  profileId: Uuid;
  profileRevision: Revision;
  instances: Array<Instance>;
  engineVersion: string;
  configurationRevision: Revision;
  judgementSnapshot: JudgementSnapshot;
  canonicalInputHash: Hash;
  checksum: Hash;
}
export interface Camera {
  target: Vec3Mm;
  yawDegrees: number;
  pitchDegrees: number;
  distanceMm: number;
}
export interface EditDraft {
  schemaVersion: 1;
  sceneId: Uuid;
  baseRevision: Revision;
  instances: Array<Instance>;
  undo: Array<Array<Instance>>;
  redo: Array<Array<Instance>>;
  camera: Camera;
}
export interface ItemDraft {
  draftId: Uuid;
  pairingId: Uuid;
  baseItemId?: Uuid;
  baseItemRevision?: Revision;
  schemaVersion: 1;
  payload: ItemVersion;
  clientRevision: Revision;
}
export interface Envelope {
  item: ItemVersion;
  profile: ProfileVersion;
  scene: SceneRevision;
  draft: EditDraft;
  inbound: ItemDraft;
}
