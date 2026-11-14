package ai.cargo.geometry

import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.SearchStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Writes the solver's actual outputs for the evidence report, so the report never restates expectations as results. */
class SearchOutcomeEvidenceTest {
    private fun row(id: String, meaning: String, expectedStatus: SearchStatus, expectedReason: String?, outcome: SolveOutcome): JsonObject {
        val completed = outcome as SolveOutcome.Completed
        assertEquals(expectedStatus, completed.search.status, id)
        assertEquals(expectedReason, completed.search.reason?.name, id)
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "meaning" to JsonPrimitive(meaning),
                "expected_status" to JsonPrimitive(expectedStatus.name),
                "expected_reason" to JsonPrimitive(expectedReason),
                "actual_status" to JsonPrimitive(completed.search.status.name),
                "actual_reason" to JsonPrimitive(completed.search.reason?.name),
                "pose_returned" to JsonPrimitive(completed.pose != null),
                "checked_candidates" to JsonPrimitive(completed.details.checkedCandidates),
                "collision_free_seen" to JsonPrimitive(completed.details.collisionFreeCandidateSeen),
                "exhausted_against_fixed" to JsonPrimitive(completed.details.exhaustedAgainstFixedPoses),
                "interrupt_cause" to JsonPrimitive(completed.details.interruptCause?.name),
            ),
        )
    }

    @Test fun record_actual_search_outcomes() {
        val solve = CandidateSearch::solveNewItem
        val rows = listOf(
            row(
                "S-SEARCH-01", "지지 조건으로 후보 전부 탈락", SearchStatus.NO_CANDIDATE_UNCERTIFIED, "SUPPORT_CONSTRAINT_UNPROVEN",
                solve(emptyList(), Fx.item(), Fx.profile(obstacles = listOf(Fx.obstacle(0, 0, 0, 1000, 10, 1000))), SearchBudget.DEFAULT, CancelSignal.NEVER, GeometryConfig.V1),
            ),
            row(
                "S-SEARCH-02", "L자 물품 증명범위 밖 소진", SearchStatus.NO_CANDIDATE_UNCERTIFIED, "PROOF_CLASS_UNSUPPORTED",
                solve(emptyList(), Fx.lItem(), Fx.profile(x = 90, y = 90, z = 90), SearchBudget.DEFAULT, CancelSignal.NEVER, GeometryConfig.V1),
            ),
            row(
                "S-SEARCH-02b", "상자 + 고정짐 1개 증명범위 밖 소진", SearchStatus.NO_CANDIDATE_UNCERTIFIED, "MULTI_ITEM_PROOF_UNSUPPORTED",
                solve(listOf(Fx.placed(1, Fx.item(1), 0, 0, 0)), Fx.item(2), Fx.profile(x = 100, y = 100, z = 100), SearchBudget.DEFAULT, CancelSignal.NEVER, GeometryConfig.V1),
            ),
            row(
                "S-SEARCH-03", "고정짐 없는 한 상자 제한모델 전수 불가", SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL, null,
                solve(emptyList(), Fx.item(1), Fx.profile(x = 150, y = 150, z = 150, obstacles = listOf(Fx.obstacle(50, 50, 50, 50, 50, 50))), SearchBudget.DEFAULT, CancelSignal.NEVER, GeometryConfig.V1),
            ),
            row(
                "S-SEARCH-04-budget", "검사 횟수 예산 소진", SearchStatus.SEARCH_INTERRUPTED, null,
                solve(emptyList(), Fx.item(1), Fx.profile(), SearchBudget(0), CancelSignal.NEVER, GeometryConfig.V1),
            ),
            row(
                "S-SEARCH-04-cancel", "사용자 취소", SearchStatus.SEARCH_INTERRUPTED, null,
                solve(emptyList(), Fx.item(1), Fx.profile(opening = DimensionsMm(900, 900, 10)), SearchBudget.DEFAULT, CancelSignal { true }, GeometryConfig.V1),
            ),
        )
        val out = File(System.getProperty("cargo.moduleRoot")).resolve("build/evidence/search-outcomes.json")
        out.parentFile.mkdirs()
        out.writeText(buildJsonArray { rows.forEach { add(it) } }.toString())
    }
}
