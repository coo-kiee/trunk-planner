package ai.cargo.scene

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.SceneRevision
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm
import ai.cargo.geometry.GeometryConfig
import kotlin.test.assertEquals
import kotlin.test.fail

/** Synthetic library. None of these dimensions describe a real vehicle. */
object SceneFx {
    const val T = "2026-11-03T00:00:00Z"
    const val T2 = "2026-11-03T01:00:00Z"
    val SCENE = uuid(500)
    val PROFILE = uuid(600)
    val ITEM_A = uuid(1)
    val ITEM_B = uuid(2)
    val INST_A = uuid(101)
    val INST_B = uuid(102)

    fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    fun item(id: String, revision: Long = 1, x: Long = 200, y: Long = 200, z: Long = 200, stackable: Boolean = true, e: Long = 0) = ItemVersion(
        schemaVersion = 1, id = id, revision = revision, createdAt = T, updatedAt = T, name = "item",
        shapeKind = ShapeKind.BOX, shapeProxy = listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, y, z))),
        measuredDimensionsMm = DimensionsMm(x, y, z), errorMm = e, measurementSource = MeasurementSource.DEMO_SYNTHETIC,
        rotationRule = RotationRule.ANY24, stackable = stackable, removalPriority = 1, confirmedDimensions = true,
    )

    fun profile(revision: Long = 1, x: Long = 1000, grade: ProfileGrade = ProfileGrade.DEMO_SYNTHETIC) = ProfileVersion(
        schemaVersion = 1, id = PROFILE, revision = revision, createdAt = T, updatedAt = T, grade = grade,
        bounds = if (grade == ProfileGrade.UNSUPPORTED) null else DimensionsMm(x, 1000, 1000),
        obstacles = emptyList(), opening = if (grade == ProfileGrade.UNSUPPORTED) null else DimensionsMm(900, 900, 50),
        errorMm = 0, provenance = "synthetic", method = "synthetic", repeatMeasurements = emptyList(),
    )

    val library = SceneContext(
        items = listOf(item(ITEM_A), item(ITEM_B), item(ITEM_A, revision = 2, x = 300)),
        profiles = listOf(profile(), profile(revision = 2, x = 1100)),
    )

    fun newDraft() = SceneDraftState.newScene(SCENE, PROFILE, 1, T)

    fun SceneDraftState.ok(c: SceneCommand, ctx: SceneContext = library): SceneDraftState =
        when (val r = SceneReducer.reduce(this, c, ctx, T2)) {
            is ReduceResult.Accepted -> r.state
            is ReduceResult.Rejected -> fail("rejected: $r for $c")
        }

    fun SceneDraftState.rejected(c: SceneCommand, ctx: SceneContext = library): ReduceResult.Rejected =
        SceneReducer.reduce(this, c, ctx, T2) as? ReduceResult.Rejected ?: fail("accepted $c")

    fun SceneDraftState.validated(ctx: SceneContext = library): SceneDraftState =
        ok(SceneCommand.ApplyValidation((SceneValidator.validate(this, ctx) as ValidationOutcome.Ready).record), ctx)

    fun SceneDraftState.confirmed(ctx: SceneContext = library, ack: List<JudgementIssue> = emptyList()): SceneDraftState {
        val v = validated(ctx)
        return v.ok(SceneCommand.ConfirmPlacement(v.validation!!.inputHash, ack), ctx)
    }

    fun SceneDraftState.saved(head: Long?, ctx: SceneContext = library): PreparedSave =
        (SavePreparation.prepare(this, head, ctx, T2) as? SaveDecision.Approved)?.save
            ?: fail("save refused: ${SavePreparation.prepare(this, head, ctx, T2)}")

    /** Saved revision 1 with item A locked at pose (0,0,0). */
    fun lockedRevision(ctx: SceneContext = library): SceneRevision =
        newDraft().ok(SceneCommand.Add(INST_A, ItemRef(ITEM_A, 1), Vec3Mm(0, 0, 0), 0, locked = true), ctx)
            .confirmed(ctx).saved(null, ctx).revision

    fun assertRejected(reason: SaveRejection, decision: SaveDecision) {
        assertEquals(reason, (decision as? SaveDecision.Rejected)?.reason, decision.toString())
    }
}
