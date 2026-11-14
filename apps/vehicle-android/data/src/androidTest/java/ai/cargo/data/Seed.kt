package ai.cargo.data

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm
import ai.cargo.scene.ItemRef
import ai.cargo.scene.PreparedSave
import ai.cargo.scene.ReduceResult
import ai.cargo.scene.SaveDecision
import ai.cargo.scene.SavePreparation
import ai.cargo.scene.SceneCommand
import ai.cargo.scene.SceneContext
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.SceneReducer
import ai.cargo.scene.SceneValidator
import ai.cargo.scene.ValidationOutcome
import android.content.Context

/** Synthetic scenes for this app's own test databases. No dimension here describes a real vehicle. */
object Seed {
    const val T = "2026-11-03T00:00:00Z"
    val SCENE = uuid(500)
    val PROFILE = uuid(600)
    val ITEM_A = uuid(1)
    val ITEM_B = uuid(2)
    val INST_A = uuid(101)
    val INST_B = uuid(102)
    val ORIGIN = Vec3Mm(0, 0, 0)
    val POSE_B = Vec3Mm(400, 0, 0)

    fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    fun item(id: String, revision: Long = 1, x: Long = 200) = ItemVersion(
        schemaVersion = 1, id = id, revision = revision, createdAt = T, updatedAt = T, name = "시험 물품",
        shapeKind = ShapeKind.BOX, shapeProxy = listOf(BoxProxy(ORIGIN, DimensionsMm(x, 200, 200))),
        measuredDimensionsMm = DimensionsMm(x, 200, 200), errorMm = 0, measurementSource = MeasurementSource.DEMO_SYNTHETIC,
        rotationRule = RotationRule.ANY24, stackable = true, removalPriority = 1, confirmedDimensions = true,
    )

    fun profile(revision: Long = 1) = ProfileVersion(
        schemaVersion = 1, id = PROFILE, revision = revision, createdAt = T, updatedAt = T, grade = ProfileGrade.DEMO_SYNTHETIC,
        bounds = DimensionsMm(2000, 1000, 2000), obstacles = emptyList(), opening = DimensionsMm(1800, 900, 50),
        errorMm = 0, provenance = "합성 시연용·실제차량 치수 아님", method = "synthetic fixture", repeatMeasurements = emptyList(),
    )

    fun open(context: Context, name: String, nowMs: Long = 1_000): CargoStore =
        (StoreOpener.open(context, name, nowMs) as StoreOpen.Ready).store

    fun library(store: CargoStore) {
        store.library.saveItemVersion(item(ITEM_A))
        store.library.saveItemVersion(item(ITEM_B))
        store.library.saveProfileVersion(profile())
    }

    fun SceneDraftState.apply(command: SceneCommand, context: SceneContext): SceneDraftState =
        (SceneReducer.reduce(this, command, context, T) as ReduceResult.Accepted).state

    fun SceneDraftState.confirmed(context: SceneContext): SceneDraftState {
        val record = (SceneValidator.validate(this, context) as ValidationOutcome.Ready).record
        return apply(SceneCommand.ApplyValidation(record), context).apply(SceneCommand.ConfirmPlacement(record.inputHash), context)
    }

    fun prepare(state: SceneDraftState, head: Long?, context: SceneContext): PreparedSave =
        (SavePreparation.prepare(state.confirmed(context), head, context, T) as SaveDecision.Approved).save

    /** Library plus revision 1 with item A locked at the origin. */
    fun firstRevision(store: CargoStore) {
        library(store)
        val context = store.library.sceneContext(T)
        val draft = SceneDraftState.newScene(SCENE, PROFILE, 1, T)
            .apply(SceneCommand.Add(INST_A, ItemRef(ITEM_A, 1), ORIGIN, 0, locked = true), context)
        check(store.scenes.save(prepare(draft, null, context)) is SaveResult.Saved)
    }

    /** Next save adds item B next to the locked item A. */
    fun secondRevision(store: CargoStore): PreparedSave {
        val context = store.library.sceneContext(T)
        val head = (store.scenes.load(SCENE, T) as SceneLoad.Healthy).revision
        val draft = SceneDraftState.fromRevision(head)
            .apply(SceneCommand.Add(INST_B, ItemRef(ITEM_B, 1), POSE_B, 0), context)
        return prepare(draft, head.revision, context)
    }
}
