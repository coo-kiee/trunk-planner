package ai.cargo.feature.editor

import ai.cargo.contracts.*
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.scene.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wording and status rules only; real screens are checked on the emulator by the scenario runner. */
class JudgementPresenterTest {
    private val t = "2026-11-04T00:00:00Z"
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun item(rev: Long = 1, x: Long = 200) = ItemVersion(
        1, uuid(1), rev, t, t, "상자", null, ShapeKind.BOX, listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, 200, 200))),
        DimensionsMm(x, 200, 200), 0, MeasurementSource.USER_TAPE, RotationRule.ANY24, true, 1, true,
    )
    private fun profile(opening: DimensionsMm?) = ProfileVersion(
        1, uuid(2), 1, t, t, ProfileGrade.DEMO_SYNTHETIC, DimensionsMm(1000, 1000, 1000), emptyList(), opening, 0, "x", null, null, "m", emptyList(),
    )
    private fun apply(s: SceneDraftState, c: SceneCommand, ctx: SceneContext) = (SceneReducer.reduce(s, c, ctx, t) as ReduceResult.Accepted).state
    private fun stateOf(draft: SceneDraftState, ctx: SceneContext) = EditorUiState(mode = EditorMode.Editable, draft = draft, context = ctx)
    private fun validated(s: SceneDraftState, ctx: SceneContext) =
        apply(s, SceneCommand.ApplyValidation((SceneValidator.validate(s, ctx) as ValidationOutcome.Ready).record), ctx)
    private fun row(b: JudgementBoard, id: String) = b.rows.first { it.id == id }

    @Test fun nothingLooksClearBeforeJudgement() {
        val ctx = SceneContext(listOf(item()), listOf(profile(null)))
        val draft = apply(SceneDraftState.newScene(uuid(3), uuid(2), 1, t), SceneCommand.Add(uuid(4), ItemRef(uuid(1), 1), Vec3Mm(0, 0, 0), 0), ctx)
        val board = JudgementPresenter.board(stateOf(draft, ctx))
        assertTrue(board.rows.none { it.kind == CargoStatusKind.CLEAR })
        assertFalse(board.fitsConfirmed)
    }

    @Test fun openingUnmeasuredIsNotAPass() {
        val ctx = SceneContext(listOf(item()), listOf(profile(null)))
        val draft = validated(apply(SceneDraftState.newScene(uuid(3), uuid(2), 1, t), SceneCommand.Add(uuid(4), ItemRef(uuid(1), 1), Vec3Mm(0, 0, 0), 0), ctx), ctx)
        val opening = row(JudgementPresenter.board(stateOf(draft, ctx)), "opening")
        assertEquals("OPENING_UNMEASURED", opening.code)
        assertEquals(CargoStatusKind.UNVERIFIED, opening.kind)
    }

    @Test fun staleHidesEverything() {
        val ctx = SceneContext(listOf(item(1), item(2, 300)), listOf(profile(DimensionsMm(900, 900, 1))))
        val validated = validated(apply(SceneDraftState.newScene(uuid(3), uuid(2), 1, t), SceneCommand.Add(uuid(4), ItemRef(uuid(1), 1), Vec3Mm(0, 0, 0), 0), ctx), ctx)
        val stale = apply(validated, SceneCommand.ApplyItemVersion(uuid(4), 2), ctx)
        val board = JudgementPresenter.board(stateOf(stale, ctx))
        assertTrue(board.rows.filter { it.id in setOf("placement", "opening", "path", "handling") }.all { it.kind == CargoStatusKind.STALE })
        assertEquals(listOf(StaleReason.ITEM_VERSION_APPLIED), board.staleReasons)
        assertFalse(board.fitsConfirmed)
    }

    @Test fun safetyIsAlwaysNotAssessed() {
        val ctx = SceneContext(listOf(item()), listOf(profile(null)))
        val board = JudgementPresenter.board(stateOf(SceneDraftState.newScene(uuid(3), uuid(2), 1, t), ctx))
        assertEquals(JudgementPresenter.NOT_ASSESSED_TEXT, row(board, "safety").text)
    }

    @Test fun moveWordingFollowsSign() {
        assertEquals("차량 왼쪽으로 100mm 이동", EditorRules.moveLabel(MoveDirection.VEHICLE_LEFT, 100))
        assertEquals("차량 오른쪽으로 50mm 이동", EditorRules.moveLabel(MoveDirection.VEHICLE_RIGHT, 50))
    }
}
