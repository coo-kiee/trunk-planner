package ai.cargo.geometry

import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.SearchStatus
import ai.cargo.contracts.UncertifiedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandidateSearchTest {
    private fun completed(fixed: List<PlacedInput>, item: ai.cargo.contracts.ItemVersion, p: ai.cargo.contracts.ProfileVersion, budget: SearchBudget = SearchBudget.DEFAULT) =
        CandidateSearch.solveNewItem(fixed, item, p, budget) as SolveOutcome.Completed

    @Test fun found_returns_a_supported_pose_for_the_new_item() {
        val fixed = listOf(Fx.placed(1, Fx.item(1, x = 500, y = 100, z = 1000), 0, 0, 0))
        val r = completed(fixed, Fx.item(2), Fx.profile())
        assertEquals(SearchStatus.FOUND_PLACEMENT, r.search.status)
        val pose = assertNotNull(r.pose)
        assertEquals(PlacementStatus.CLEAR, r.details.placementAtCandidate)
        val g = Fx.judged(fixed + PlacedInput(Fx.uuid(2), Fx.item(2), pose.poseMm, pose.orientationIndex), Fx.profile())
        assertEquals(PlacementStatus.CLEAR, g.placement.status)
        assertTrue(g.handlingIssues.isEmpty())
    }

    @Test fun found_can_carry_uncertain_placement_when_error_decides() {
        val p = Fx.profile(x = 100, y = 100, z = 100)
        val r = completed(emptyList(), Fx.item(1, e = 2), p)
        assertEquals(SearchStatus.FOUND_PLACEMENT, r.search.status)
        assertEquals(PlacementStatus.UNCERTAIN, r.details.placementAtCandidate)
    }

    @Test fun S_SEARCH_01_support_rejection_is_uncertified_not_impossible() {
        // Obstacle covers the floor: collision-free poses exist only on top of it, and its top carries nothing.
        val p = Fx.profile(obstacles = listOf(Fx.obstacle(0, 0, 0, 1000, 10, 1000)))
        val r = completed(emptyList(), Fx.item(), p)
        assertEquals(SearchStatus.NO_CANDIDATE_UNCERTIFIED, r.search.status)
        assertEquals(UncertifiedReason.SUPPORT_CONSTRAINT_UNPROVEN, r.search.reason)
        assertTrue(r.details.collisionFreeCandidateSeen)
        assertNull(r.pose)
    }

    @Test fun S_SEARCH_02_l_shape_exhaustion_is_proof_class_unsupported() {
        val r = completed(emptyList(), Fx.lItem(), Fx.profile(x = 90, y = 90, z = 90))
        assertEquals(SearchStatus.NO_CANDIDATE_UNCERTIFIED, r.search.status)
        assertEquals(UncertifiedReason.PROOF_CLASS_UNSUPPORTED, r.search.reason)
        val cylinder = Fx.item(1, x = 2000).copy(shapeKind = ai.cargo.contracts.ShapeKind.CYLINDER_BOUNDING_BOX)
        assertEquals(UncertifiedReason.PROOF_CLASS_UNSUPPORTED, completed(emptyList(), cylinder, Fx.profile(x = 90, y = 90, z = 90)).search.reason)
    }

    @Test fun S_SEARCH_02b_box_with_one_fixed_item_is_multi_item_unsupported() {
        val p = Fx.profile(x = 100, y = 100, z = 100)
        val r = completed(listOf(Fx.placed(1, Fx.item(1), 0, 0, 0)), Fx.item(2), p)
        assertEquals(SearchStatus.NO_CANDIDATE_UNCERTIFIED, r.search.status)
        assertEquals(UncertifiedReason.MULTI_ITEM_PROOF_UNSUPPORTED, r.search.reason)
        assertTrue(r.details.exhaustedAgainstFixedPoses)
    }

    @Test fun S_SEARCH_03_single_box_exhaustion_is_certified_in_model_only() {
        val r = completed(emptyList(), Fx.item(1, x = 2000, y = 2000, z = 2000), Fx.profile())
        assertEquals(SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL, r.search.status)
        assertNull(r.search.reason)
        val blocked = Fx.profile(x = 300, y = 100, z = 100, obstacles = listOf(Fx.obstacle(100, 0, 0, 100, 100, 100)))
        val small = Fx.item(1, x = 150, y = 100, z = 100, rule = RotationRule.KEEP_UPRIGHT)
        assertEquals(SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL, completed(emptyList(), small, blocked).search.status)
        assertEquals(false, completed(emptyList(), Fx.item(1, x = 2000, y = 2000, z = 2000), Fx.profile()).details.exhaustedAgainstFixedPoses)
    }

    @Test fun certified_uses_best_bounds_so_error_can_prevent_it() {
        // Only 1mm short at nominal; at best bounds it would fit, so the search finds a doubtful pose instead of a proof.
        val p = Fx.profile(x = 100, y = 100, z = 100, es = 2)
        val r = completed(emptyList(), Fx.item(1, x = 101, rule = RotationRule.KEEP_UPRIGHT), p)
        assertEquals(SearchStatus.FOUND_PLACEMENT, r.search.status)
        assertEquals(PlacementStatus.UNCERTAIN, r.details.placementAtCandidate)
    }

    @Test fun S_SEARCH_04_budget_and_cancel_are_interrupted_with_no_pose() {
        val p = Fx.profile()
        val budget = completed(emptyList(), Fx.item(1), Fx.profile(obstacles = listOf(Fx.obstacle(0, 0, 0, 50, 50, 50))), SearchBudget(0))
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, budget.search.status)
        assertNull(budget.search.reason)
        assertNull(budget.pose)
        assertEquals(InterruptCause.BUDGET, budget.details.interruptCause)
        val cancelled = CandidateSearch.solveNewItem(emptyList(), Fx.item(), p, SearchBudget.DEFAULT, CancelSignal { true }) as SolveOutcome.Completed
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, cancelled.search.status)
        assertEquals(InterruptCause.CANCELLED, cancelled.details.interruptCause)
        assertNull(cancelled.pose)
    }

    @Test fun CE06_budget_exhausted_with_a_doubtful_fallback_returns_no_pose() {
        // Every pose here is doubtful, so a full run reports FOUND with UNCERTAIN placement from a remembered fallback.
        val p = Fx.profile(x = 100, y = 100, z = 100, es = 2)
        val item = Fx.item(1, e = 2, rule = RotationRule.KEEP_UPRIGHT)
        val full = completed(emptyList(), item, p)
        assertEquals(SearchStatus.FOUND_PLACEMENT, full.search.status)
        assertEquals(PlacementStatus.UNCERTAIN, full.details.placementAtCandidate)
        assertTrue(full.details.checkedCandidates > 1)
        // Stopping after the fallback exists but before the scan ends must not turn that fallback into a result.
        val cut = completed(emptyList(), item, p, SearchBudget(full.details.checkedCandidates - 1))
        assertEquals(true, cut.details.collisionFreeCandidateSeen)
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, cut.search.status)
        assertNull(cut.pose)
    }

    @Test fun budget_count_is_deterministic_and_reproducible() {
        // Every 100mm cube in a 150mm space contains the central obstacle, so all candidates get evaluated and fail.
        val p = Fx.profile(x = 150, y = 150, z = 150, obstacles = listOf(Fx.obstacle(50, 50, 50, 50, 50, 50)))
        val a = completed(emptyList(), Fx.item(1), p)
        val b = completed(emptyList(), Fx.item(1), p)
        assertEquals(a, b)
        assertEquals(SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL, a.search.status)
        val limit = a.details.checkedCandidates
        assertTrue(limit > 0)
        assertEquals(a.search, completed(emptyList(), Fx.item(1), p, SearchBudget(limit)).search)
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, completed(emptyList(), Fx.item(1), p, SearchBudget(limit - 1)).search.status)
    }

    @Test fun CE07_keep_upright_candidates_never_lie_down_and_non_stackable_is_not_a_base() {
        val tall = Fx.item(1, x = 100, y = 400, z = 100, rule = RotationRule.KEEP_UPRIGHT)
        val low = Fx.profile(x = 1000, y = 200, z = 1000)
        val r = completed(emptyList(), tall, low)
        assertEquals(SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL, r.search.status, "only lying down would fit under a low roof")
        val found = completed(emptyList(), tall, Fx.profile())
        assertTrue(found.pose!!.orientationIndex.toInt() in Orientations.allowed(RotationRule.KEEP_UPRIGHT, Vec(100, 400, 100)))

        val base = Fx.placed(1, Fx.item(1, x = 100, y = 100, z = 100, stackable = false), 0, 0, 0)
        val p = Fx.profile(x = 100, y = 300, z = 100)
        val blocked = completed(listOf(base), Fx.item(2, rule = RotationRule.KEEP_UPRIGHT), p)
        assertEquals(SearchStatus.NO_CANDIDATE_UNCERTIFIED, blocked.search.status)
        val stackableBase = base.copy(item = Fx.item(1, stackable = true))
        val stacked = completed(listOf(stackableBase), Fx.item(2, rule = RotationRule.KEEP_UPRIGHT), p)
        assertEquals(100, stacked.pose!!.poseMm.y)
    }

    @Test fun CE08_random_scenes_keep_fixed_values_identical() {
        val rnd = java.util.Random(20261103)
        repeat(300) {
            val fixed = (1..(1 + rnd.nextInt(5))).map { n ->
                Fx.placed(n, Fx.item(n, x = 50L + rnd.nextInt(200), y = 50L + rnd.nextInt(200), z = 50L + rnd.nextInt(200)), rnd.nextInt(800).toLong(), 0, rnd.nextInt(800).toLong(), rnd.nextInt(24).toLong())
            }
            val before = fixed.toList().map { it.copy() }
            val outcome = CandidateSearch.solveNewItem(fixed, Fx.item(90, x = 100, y = 100, z = 100), Fx.profile(), SearchBudget(5000))
            assertEquals(before, fixed)
            if (outcome is SolveOutcome.Completed && outcome.pose != null) {
                val combined = fixed + PlacedInput(Fx.uuid(90), Fx.item(90), outcome.pose!!.poseMm, outcome.pose!!.orientationIndex)
                val again = Fx.judged(combined, Fx.profile())
                assertEquals(before, combined.take(fixed.size), "judgement cannot return moved fixed items")
                assertTrue(again.placement.issues.none { it.instanceId == Fx.uuid(90) || it.otherInstanceId == Fx.uuid(90) } || outcome.details.placementAtCandidate != PlacementStatus.CLEAR)
            }
        }
    }

    @Test fun unsupported_profile_gives_typed_not_available() {
        val r = CandidateSearch.solveNewItem(emptyList(), Fx.item(), Fx.profile(grade = ProfileGrade.UNSUPPORTED))
        assertEquals(NotAvailableReason.PROFILE_UNSUPPORTED, (r as SolveOutcome.NotAvailable).reason)
    }

    /** Oracle: every integer min corner on a small grid. CERTIFIED must never coexist with a free pose. */
    @Test fun seeded_small_grid_oracle_rejects_false_certification() {
        val rnd = java.util.Random(7)
        var certified = 0
        var foundCount = 0
        repeat(1500) {
            val bx = 6L + rnd.nextInt(8)
            val by = 6L + rnd.nextInt(8)
            val bz = 6L + rnd.nextInt(8)
            val es = rnd.nextInt(2).toLong()
            val obstacles = (0 until rnd.nextInt(5)).map {
                Fx.obstacle(rnd.nextInt(bx.toInt()).toLong(), rnd.nextInt(by.toInt()).toLong(), rnd.nextInt(bz.toInt()).toLong(), 1L + rnd.nextInt(5), 1L + rnd.nextInt(5), 1L + rnd.nextInt(5))
            }
            val p = Fx.profile(bx, by, bz, es, obstacles = obstacles)
            val e = rnd.nextInt(2).toLong()
            val item = Fx.item(1, 1L + rnd.nextInt(9), 1L + rnd.nextInt(9), 1L + rnd.nextInt(9), e, RotationRule.entries[rnd.nextInt(3)])
            val r = completed(emptyList(), item, p, SearchBudget(1_000_000))
            val free = bruteForceFree(item, p)
            when (r.search.status) {
                SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL -> { certified++; assertTrue(!free, "false certification for $item in $p") }
                SearchStatus.FOUND_PLACEMENT -> foundCount++
                else -> assertTrue(free, "uncertified claims a collision-free candidate existed: $item $p")
            }
            if (free) assertTrue(r.search.status != SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL)
        }
        assertTrue(certified > 50 && foundCount > 50, "oracle coverage certified=$certified found=$foundCount")
    }

    private fun bruteForceFree(item: ai.cargo.contracts.ItemVersion, p: ai.cargo.contracts.ProfileVersion): Boolean {
        val config = GeometryConfig.V1
        val geometry = ItemGeometry.of(item, config)
        val space = ProfileSpace.of(p, config)
        val cargo = space.space(Bound.BEST)
        val solid = space.obstacles(Bound.BEST)
        return geometry.allowedOrientations().any { index ->
            val shape = geometry.oriented(index)
            (cargo.minX..cargo.maxX).any { x -> (cargo.minY..cargo.maxY).any { y -> (cargo.minZ..cargo.maxZ).any { z ->
                shape.boxesAt(Vec(x, y, z), Bound.BEST, geometry.errorMm).all { it.isInside(cargo) && solid.none(it::overlaps) }
            } } }
        }
    }

    @Test fun RV_F03_found_pose_keeps_clear_of_the_origin_walls_by_the_profile_error() {
        val r = completed(emptyList(), Fx.item(1, e = 5, rule = RotationRule.KEEP_UPRIGHT), Fx.profile(es = 10))
        assertEquals(PlacementStatus.CLEAR, r.details.placementAtCandidate)
        assertTrue(r.pose!!.poseMm.x >= 10 && r.pose!!.poseMm.z >= 10, "clear pose stays off x=0 and z=0 by the error: ${r.pose}")
        // Proof grid starts at the best lower wall, so a pose only possible there is still found, never certified away.
        val tight = Fx.profile(x = 100, y = 100, z = 100, es = 2)
        val wide = completed(emptyList(), Fx.item(1, x = 103, rule = RotationRule.KEEP_UPRIGHT), tight)
        assertEquals(SearchStatus.FOUND_PLACEMENT, wide.search.status)
        assertEquals(-2, wide.pose!!.poseMm.x)
        assertEquals(PlacementStatus.UNCERTAIN, wide.details.placementAtCandidate)
        assertEquals(SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL, completed(emptyList(), Fx.item(1, x = 105, rule = RotationRule.KEEP_UPRIGHT), tight).search.status)
    }

    @Test fun l_shape_can_be_found_and_placed_without_a_proof_claim() {
        val r = completed(emptyList(), Fx.lItem(), Fx.profile(x = 400, y = 200, z = 400))
        assertEquals(SearchStatus.FOUND_PLACEMENT, r.search.status)
    }

    @Test fun opening_is_not_a_search_input() {
        val r = completed(emptyList(), Fx.item(), Fx.profile(opening = DimensionsMm(1, 1, 1)))
        assertEquals(SearchStatus.FOUND_PLACEMENT, r.search.status)
    }
}
