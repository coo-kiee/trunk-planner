package ai.cargo.planner

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm
import ai.cargo.data.CargoStore
import ai.cargo.data.SaveResult
import ai.cargo.feature.editor.EditorRules
import ai.cargo.geometry.GeometryConfig
import ai.cargo.scene.ItemRef
import ai.cargo.scene.ReduceResult
import ai.cargo.scene.SaveDecision
import ai.cargo.scene.SavePreparation
import ai.cargo.scene.SceneCommand
import ai.cargo.scene.SceneContext
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.SceneReducer
import ai.cargo.scene.SceneSearch
import ai.cargo.scene.SceneValidator
import ai.cargo.scene.ValidationOutcome
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * Acceptance data written through the real reducer, judge and Room store, so what a screen reads is a genuine stored
 * state. Evidence calls this grade seeded_real_store: it is not a fixture of UI state and not a user-typed flow.
 * Every dimension here is synthetic and no profile claims to describe a real vehicle.
 */
class DemoSeeds(private val app: CargoApp) {
    private val graph get() = app.graph
    private val t get() = graph.isoNow()
    private fun id() = UUID.randomUUID().toString()

    private data class Place(val item: ItemVersion, val pose: Vec3Mm, val locked: Boolean = true, val orientation: Long = 0)

    fun run(name: String): String = when (name) {
        "CE03A" -> saved("입구 통과·내부 충돌", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1))) { p ->
            val a = item("앞상자"); val b = item("겹친상자")
            listOf(Place(a, Vec3Mm(0, 0, 0)), Place(b, Vec3Mm(250, 0, 0)))
        }
        "CE03B" -> saved("내부 여유·입구 불통과", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(150, 150, 1))) { _ ->
            listOf(Place(item("큰상자"), Vec3Mm(0, 0, 0)))
        }
        "CE04" -> saved("입구 미측정", profile(DimensionsMm(1500, 900, 1500), null)) { _ -> listOf(Place(item("상자"), Vec3Mm(0, 0, 0))) }
        "CE05" -> saved("입구 통과·경로 미검사", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1))) { _ -> listOf(Place(item("상자"), Vec3Mm(0, 0, 0))) }
        "BASIC" -> saved("기본", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1))) { _ -> listOf(Place(item("기준상자"), Vec3Mm(0, 0, 0), locked = false)) }
        "CE06DEATH" -> interruptedDraft()
        "CE11ENGINE" -> saved("엔진 변경", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1)), GeometryConfig.V1.copy(engineVersion = "aabb-mm-v0-old")) { _ ->
            listOf(Place(item("상자"), Vec3Mm(0, 0, 0)))
        }
        "CE11CONFIG" -> saved("설정 변경", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1)), GeometryConfig.V1.copy(revision = 99)) { _ ->
            listOf(Place(item("상자"), Vec3Mm(0, 0, 0)))
        }
        "CE11PROFILE" -> newerProfile()
        "CE13CHECKSUM" -> corruptHead()
        "CE13FUTURE" -> setVersion(99)
        "CE14MIGRATION" -> setVersion(1)
        "SEARCH05" -> searchFull()
        "OCCLUSION" -> occlusion()
        "PERF20" -> perf(20)
        "PERF100" -> perf(100)
        "UNSUPPORTEDSCENE" -> unsupportedScene()
        else -> error("Unknown seed $name")
    }

    private val store: CargoStore get() = graph.store ?: error("Store not ready")

    private fun item(name: String, x: Long = 300, y: Long = 200, z: Long = 300, rule: RotationRule = RotationRule.ANY24) = ItemVersion(
        schemaVersion = 1, id = id(), revision = 1, createdAt = t, updatedAt = t, name = name, shapeKind = ShapeKind.BOX,
        shapeProxy = listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, y, z))), measuredDimensionsMm = DimensionsMm(x, y, z), errorMm = 0,
        measurementSource = MeasurementSource.DEMO_SYNTHETIC, rotationRule = rule, stackable = true, removalPriority = 1, confirmedDimensions = true,
    ).also { store.library.saveItemVersion(it) }

    private fun profile(bounds: DimensionsMm, opening: DimensionsMm?, revision: Long = 1, profileId: String = id()) = ProfileVersion(
        schemaVersion = 1, id = profileId, revision = revision, createdAt = t, updatedAt = t, grade = ProfileGrade.DEMO_SYNTHETIC,
        bounds = bounds, obstacles = emptyList(), opening = opening, errorMm = 0, provenance = "합성 시연용·실제 차량 치수 아님",
        method = "synthetic seed", repeatMeasurements = emptyList(),
    ).also {
        store.library.saveProfileVersion(it)
        graph.index.addProfile(it.id)
    }

    private fun SceneDraftState.apply(command: SceneCommand, context: SceneContext) =
        (SceneReducer.reduce(this, command, context, t) as ReduceResult.Accepted).state

    private fun saved(label: String, p: ProfileVersion, config: GeometryConfig = GeometryConfig.V1, places: (ProfileVersion) -> List<Place>): String {
        val list = places(p)
        val context = store.library.sceneContext(t, config)
        var draft = EditorRules.newDraft(id(), p, t)
        list.forEach { place ->
            draft = draft.apply(SceneCommand.Add(id(), ItemRef(place.item.id, place.item.revision), place.pose, place.orientation, place.locked), context)
        }
        val record = (SceneValidator.validate(draft, context) as ValidationOutcome.Ready).record
        draft = draft.apply(SceneCommand.ApplyValidation(record), context)
            .apply(SceneCommand.ConfirmPlacement(record.inputHash, record.issues), context)
        val decision = SavePreparation.prepare(draft, null, context, t) as SaveDecision.Approved
        check(store.scenes.save(decision.save) is SaveResult.Saved)
        return "$label scene=${draft.sceneId}"
    }

    /** A search marker on a stored draft is exactly what a process dying mid-search leaves behind. */
    private fun interruptedDraft(): String {
        val p = profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1))
        val a = item("탐색대상")
        val context = store.library.sceneContext(t)
        val sceneId = id()
        val draft = EditorRules.newDraft(sceneId, p, t)
            .apply(SceneCommand.BeginSearch(id(), ItemRef(a.id, a.revision)), context)
        store.drafts.commit(draft, t)
        graph.index.addUnsaved(sceneId)
        return "scene=$sceneId"
    }

    private fun newerProfile(): String {
        val first = profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1))
        val result = saved("공간 새 버전 대기", first) { _ -> listOf(Place(item("상자"), Vec3Mm(0, 0, 0))) }
        profile(DimensionsMm(1400, 900, 1500), DimensionsMm(1200, 800, 1), revision = 2, profileId = first.id)
        return result
    }

    /** Real damage to the stored bytes of the current head, on the file Room uses. */
    private fun corruptHead(): String {
        val path = app.getDatabasePath(AppGraph.DB_NAME).path
        graph.store?.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // The product's trigger refuses this, so the damage has to step around it as a failing disk would.
            db.execSQL("DROP TRIGGER immutable_scene_revision_update")
            db.execSQL(
                "UPDATE scene_revision SET json = replace(json, '\"poseMm\":{\"x\":0', '\"poseMm\":{\"x\":7') " +
                    "WHERE (sceneId, revision) IN (SELECT sceneId, headRevision FROM scene_head)",
            )
        }
        return "damaged"
    }

    /** Real file header version. 99 is a newer app; 1 makes the real migration run on a table that does not fit it. */
    private fun setVersion(version: Int): String {
        val path = app.getDatabasePath(AppGraph.DB_NAME).path
        graph.store?.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.version = version }
        return "user_version=$version"
    }

    /** One fixed item fills the floor, and the new item fits nowhere: search runs to exhaustion against a fixed item. */
    private fun searchFull(): String {
        item("빈자리 없는 큰짐", 700, 500, 700)
        return saved("후보 소진", profile(DimensionsMm(800, 600, 800), DimensionsMm(790, 590, 1))) { _ ->
            listOf(Place(item("고정된 큰짐", 600, 400, 600), Vec3Mm(100, 0, 100)))
        }
    }

    /** Two boxes on the same line of sight, so depth must decide which color shows. */
    private fun occlusion(): String = saved("가림", profile(DimensionsMm(1500, 900, 1500), DimensionsMm(1200, 800, 1))) { _ ->
        listOf(
            Place(item("앞쪽상자", 400, 400, 200), Vec3Mm(550, 0, 200), locked = false),
            Place(item("뒤쪽상자", 400, 400, 200), Vec3Mm(550, 0, 900), locked = false),
        )
    }

    /** Dense but non-overlapping grid for renderer measurement. */
    private fun perf(count: Int): String {
        val shared = item("성능상자", 80, 80, 80)
        return saved("성능 $count", profile(DimensionsMm(2000, 1000, 2000), DimensionsMm(1800, 900, 1))) { _ ->
            List(count) { i -> Place(shared, Vec3Mm((i % 10) * 180L, (i / 50) * 80L, ((i / 10) % 5) * 180L)) }
        }
    }

    private fun unsupportedScene(): String {
        val p = ProfileVersion(
            schemaVersion = 1, id = id(), revision = 1, createdAt = t, updatedAt = t, grade = ProfileGrade.UNSUPPORTED, bounds = null,
            obstacles = emptyList(), opening = null, errorMm = 0, provenance = "지원 데이터 없음", method = "none", repeatMeasurements = emptyList(),
        )
        store.library.saveProfileVersion(p)
        graph.index.addProfile(p.id)
        val sceneId = id()
        val a = item("상자")
        val context = store.library.sceneContext(t)
        val draft = EditorRules.newDraft(sceneId, p, t)
            .apply(SceneCommand.Add(id(), ItemRef(a.id, a.revision), Vec3Mm(0, 0, 0), 0), context)
        store.drafts.commit(draft, t)
        graph.index.addUnsaved(sceneId)
        check(SceneSearch.run(draft, ItemRef(a.id, a.revision), context) is ai.cargo.scene.SearchRun.NotAvailable)
        return "scene=$sceneId"
    }
}
