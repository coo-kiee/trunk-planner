// Generated from schema.json. Do not edit.
package ai.cargo.contracts
import kotlinx.serialization.Serializable
typealias Uuid = String
typealias Revision = Long
typealias Timestamp = String
typealias Hash = String
@Serializable
data class Vec3Mm(
    val x: Long,
    val y: Long,
    val z: Long,
)
@Serializable
data class DimensionsMm(
    val x: Long,
    val y: Long,
    val z: Long,
)
@Serializable
data class BoxProxy(
    val origin: Vec3Mm,
    val size: DimensionsMm,
)
@Serializable
enum class ShapeKind { BOX, L_SHAPE, CYLINDER_BOUNDING_BOX }
@Serializable
enum class RotationRule { ANY24, KEEP_UPRIGHT, FLAT_ONLY }
@Serializable
enum class MeasurementSource { USER_TAPE, REPEATED_TAPE, PUBLISHED_PARTIAL, DEMO_SYNTHETIC }
@Serializable
enum class ProfileGrade { VERIFIED_MEASURED, USER_MEASURED, PUBLISHED_PARTIAL, DEMO_SYNTHETIC, UNSUPPORTED }
@Serializable
enum class PlacementStatus { CLEAR, CONFLICT, UNCERTAIN }
@Serializable
enum class OpeningStatus { FITS, DOES_NOT_FIT, UNCERTAIN, OPENING_UNMEASURED }
@Serializable
enum class HandlingStatus { OK, VIOLATION }
@Serializable
enum class SearchStatus { FOUND_PLACEMENT, CERTIFIED_INFEASIBLE_IN_MODEL, NO_CANDIDATE_UNCERTIFIED, SEARCH_INTERRUPTED, NOT_RUN }
@Serializable
enum class UncertifiedReason { PROOF_CLASS_UNSUPPORTED, SUPPORT_CONSTRAINT_UNPROVEN, MULTI_ITEM_PROOF_UNSUPPORTED }
@Serializable
data class SearchResult(
    val status: SearchStatus,
    val reason: UncertifiedReason ? = null,
)
@Serializable
data class PathResult(
    val status: String,
    val pathClass: String ? = null,
)
@Serializable
data class ItemVersion(
    val schemaVersion: Long,
    val id: Uuid,
    val revision: Revision,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val name: String,
    val photoRef: String ? = null,
    val shapeKind: ShapeKind,
    val shapeProxy: List<BoxProxy>,
    val measuredDimensionsMm: DimensionsMm,
    val errorMm: Long,
    val measurementSource: MeasurementSource,
    val rotationRule: RotationRule,
    val stackable: Boolean,
    val removalPriority: Long,
    val confirmedDimensions: Boolean,
)
@Serializable
data class ProfileVersion(
    val schemaVersion: Long,
    val id: Uuid,
    val revision: Revision,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val grade: ProfileGrade,
    val bounds: DimensionsMm ? = null,
    val obstacles: List<BoxProxy>,
    val opening: DimensionsMm ? = null,
    val errorMm: Long,
    val provenance: String,
    val sourceURL: String ? = null,
    val measuredAt: Timestamp ? = null,
    val method: String,
    val repeatMeasurements: List<DimensionsMm>,
)
@Serializable
data class Instance(
    val instanceId: Uuid,
    val itemId: Uuid,
    val itemRevision: Revision,
    val poseMm: Vec3Mm,
    val orientationIndex: Long,
    val locked: Boolean,
)
@Serializable
data class JudgementSnapshot(
    val placement: PlacementStatus,
    val opening: OpeningStatus,
    val path: PathResult,
    val handling: HandlingStatus,
    val search: SearchResult,
    val minimumClearanceMm: Long,
    val errorMm: Long,
    val conflictLocations: List<Vec3Mm>,
    val staleReasons: List<String>,
    val inputHash: Hash,
    val safety: String,
)
@Serializable
data class SceneRevision(
    val schemaVersion: Long,
    val id: Uuid,
    val revision: Revision,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val parentRevision: Revision ? = null,
    val profileId: Uuid,
    val profileRevision: Revision,
    val instances: List<Instance>,
    val engineVersion: String,
    val configurationRevision: Revision,
    val judgementSnapshot: JudgementSnapshot,
    val canonicalInputHash: Hash,
    val checksum: Hash,
)
@Serializable
data class Camera(
    val target: Vec3Mm,
    val yawDegrees: Long,
    val pitchDegrees: Long,
    val distanceMm: Long,
)
@Serializable
data class EditDraft(
    val schemaVersion: Long,
    val sceneId: Uuid,
    val baseRevision: Revision,
    val instances: List<Instance>,
    val undo: List<List<Instance>>,
    val redo: List<List<Instance>>,
    val camera: Camera,
)
@Serializable
data class ItemDraft(
    val draftId: Uuid,
    val pairingId: Uuid,
    val baseItemId: Uuid ? = null,
    val baseItemRevision: Revision ? = null,
    val schemaVersion: Long,
    val payload: ItemVersion,
    val clientRevision: Revision,
)
@Serializable
data class Envelope(
    val item: ItemVersion,
    val profile: ProfileVersion,
    val scene: SceneRevision,
    val draft: EditDraft,
    val inbound: ItemDraft,
)
