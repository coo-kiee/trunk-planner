package ai.cargo.geometry

import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.HandlingStatus
import ai.cargo.contracts.OpeningStatus
import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.RotationRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxisIndependenceTest {
    private val wide = DimensionsMm(900, 900, 50)

    @Test fun CE03_opening_fits_while_inside_conflicts() {
        val p = Fx.profile(opening = wide, obstacles = listOf(Fx.obstacle(0, 0, 500, 200, 200, 200)))
        val g = Fx.judged(listOf(Fx.placed(1, Fx.item(), 0, 0, 500)), p)
        assertEquals(OpeningStatus.FITS, g.opening)
        assertEquals(PlacementStatus.CONFLICT, g.placement.status)
    }

    @Test fun CE03_inside_clear_while_opening_does_not_fit() {
        val p = Fx.profile(opening = DimensionsMm(50, 50, 10))
        val g = Fx.judged(listOf(Fx.placed(1, Fx.item(), 0, 0, 0)), p)
        assertEquals(PlacementStatus.CLEAR, g.placement.status)
        assertEquals(OpeningStatus.DOES_NOT_FIT, g.opening)
    }

    @Test fun opening_can_be_uncertain_within_error_and_uses_permitted_rotations_only() {
        assertEquals(OpeningStatus.UNCERTAIN, Fx.judged(listOf(Fx.placed(1, Fx.item(e = 2), 0, 0, 0)), Fx.profile(opening = DimensionsMm(101, 101, 10))).opening)
        val tall = Fx.item(1, x = 100, y = 800, z = 100)
        val low = DimensionsMm(900, 200, 10)
        assertEquals(OpeningStatus.FITS, Fx.judged(listOf(Fx.placed(1, tall, 0, 0, 0)), Fx.profile(opening = low)).opening)
        val upright = tall.copy(rotationRule = RotationRule.KEEP_UPRIGHT)
        assertEquals(OpeningStatus.DOES_NOT_FIT, Fx.judged(listOf(Fx.placed(1, upright, 0, 0, 0)), Fx.profile(opening = low)).opening)
    }

    /** RV-F03-R1 counterexample: 7mm spare on each side of the opening is inside the 10mm error band of each edge. */
    @Test fun RV_F03_R1_opening_width_carries_error_on_both_edges() {
        val p = Fx.profile(x = 2000, y = 1000, z = 2000, es = 10, opening = DimensionsMm(1000, 800, 50))
        fun opening(width: Long) = Fx.judged(
            listOf(Fx.placed(1, Fx.item(1, x = width, y = 100, z = width, rule = RotationRule.KEEP_UPRIGHT), 500, 0, 500)),
            p, listOf(Fx.uuid(1)),
        )
        val sevenEachSide = opening(986)
        assertEquals(OpeningStatus.UNCERTAIN, sevenEachSide.opening, "review counterexample")
        assertEquals(PathVerdict.UNVERIFIED, sevenEachSide.path)
        // Exactly the worst window (1000 - 2*10) fits; the best window (1000 + 2*10) is the last that can.
        assertEquals(OpeningStatus.FITS, opening(980).opening)
        assertEquals(OpeningStatus.UNCERTAIN, opening(981).opening)
        assertEquals(OpeningStatus.UNCERTAIN, opening(1020).opening)
        assertEquals(OpeningStatus.DOES_NOT_FIT, opening(1021).opening)
        // Height keeps the floor as its reference: only the top edge moves.
        val tall = Fx.profile(x = 2000, y = 1000, z = 2000, es = 10, opening = DimensionsMm(1000, 800, 50))
        val upright = { h: Long -> Fx.judged(listOf(Fx.placed(1, Fx.item(1, x = 100, y = h, z = 100, rule = RotationRule.KEEP_UPRIGHT), 500, 0, 500)), tall).opening }
        assertEquals(OpeningStatus.FITS, upright(790))
        assertEquals(OpeningStatus.UNCERTAIN, upright(791))
        assertEquals(OpeningStatus.DOES_NOT_FIT, upright(811))
    }

    /** Opening fit and route cross-section share one window: FITS always has a worst-bound entry. */
    @Test fun RV_F03_R1_fits_implies_a_verified_route_into_free_space() {
        val rnd = java.util.Random(31)
        var fits = 0
        repeat(2000) {
            val es = rnd.nextInt(15).toLong()
            val e = rnd.nextInt(6).toLong()
            val opening = DimensionsMm(50L + rnd.nextInt(400), 50L + rnd.nextInt(400), 10)
            val item = Fx.item(1, 1L + rnd.nextInt(450), 1L + rnd.nextInt(450), 1L + rnd.nextInt(100), e, RotationRule.KEEP_UPRIGHT)
            // Large empty space; the item sits deep inside, clear of every wall by more than the error.
            val p = Fx.profile(x = 2000, y = 1000, z = 2000, es = es, opening = opening)
            val first = Fx.judged(listOf(Fx.placed(1, item, 100, 0, 1500)), p, listOf(Fx.uuid(1)))
            if (first.path == PathVerdict.VERIFIED_IN_MODEL) assertEquals(OpeningStatus.FITS, first.opening, "route verified but opening not FITS: $item es=$es $opening")
            if (first.opening == OpeningStatus.FITS) {
                fits++
                // The route class has no rotation in motion, so check it in the orientation the opening fit used.
                val orientation = first.openingDetails.single().orientationIndex!!.toLong()
                val g = Fx.judged(listOf(Fx.placed(1, item, 100, 0, 1500, orientation)), p, listOf(Fx.uuid(1)))
                assertEquals(PathVerdict.VERIFIED_IN_MODEL, g.path, "FITS without a worst entry: $item es=$es opening=$opening o=$orientation")
            }
        }
        assertTrue(fits > 100, "coverage $fits")
    }

    @Test fun CE04_unmeasured_opening_is_not_a_pass() {
        val g = Fx.judged(listOf(Fx.placed(1, Fx.item(), 0, 0, 0)), Fx.profile(opening = null), listOf(Fx.uuid(1)))
        assertEquals(OpeningStatus.OPENING_UNMEASURED, g.opening)
        assertEquals(PathVerdict.UNVERIFIED, g.path)
        assertEquals(PathNote.OPENING_UNMEASURED, g.pathDetails.single().note)
    }

    @Test fun CE05_opening_fits_but_path_not_run_stays_unverified() {
        val g = Fx.judged(listOf(Fx.placed(1, Fx.item(), 0, 0, 0)), Fx.profile(opening = wide), subjects = emptyList())
        assertEquals(OpeningStatus.FITS, g.opening)
        assertEquals(PathVerdict.UNVERIFIED, g.path)
    }

    @Test fun empty_scene_claims_no_opening_or_path_pass() {
        val g = Fx.judged(emptyList(), Fx.profile(opening = wide))
        assertEquals(OpeningStatus.UNCERTAIN, g.opening)
        assertEquals(PathVerdict.UNVERIFIED, g.path)
    }

    @Test fun CE09_airborne_and_partial_support_are_violations_and_safety_is_never_assessed() {
        val air = Fx.judged(listOf(Fx.placed(1, Fx.item(), 0, 50, 0)), Fx.profile())
        assertEquals(PlacementStatus.CLEAR, air.placement.status)
        assertEquals(HandlingStatus.VIOLATION, air.handling)
        assertEquals(HandlingIssueKind.AIRBORNE, air.handlingIssues.single().kind)
        assertEquals("NOT_ASSESSED", air.safety)
        val base = Fx.placed(1, Fx.item(1), 0, 0, 0)
        val overhang = Fx.placed(2, Fx.item(2), 50, 100, 0)
        val partial = Fx.judged(listOf(base, overhang), Fx.profile())
        val issue = partial.handlingIssues.single { it.instanceId == Fx.uuid(2) }
        assertEquals(HandlingIssueKind.INSUFFICIENT_SUPPORT, issue.kind)
        assertEquals(5000, partial.support[1].supportedAreaMm2)
        assertEquals("NOT_ASSESSED", partial.safety)
    }

    @Test fun obstacle_top_does_not_count_as_support() {
        val p = Fx.profile(obstacles = listOf(Fx.obstacle(0, 0, 0, 500, 100, 500)))
        val g = Fx.judged(listOf(Fx.placed(1, Fx.item(), 0, 100, 0)), p)
        assertEquals(HandlingIssueKind.AIRBORNE, g.handlingIssues.single().kind)
    }

    @Test fun support_union_does_not_double_count_overlapping_carriers() {
        // Two overlapping stackable bases cover 100x100 of a 150x100 bottom when unioned, 150x100 if summed naively.
        val a = Fx.placed(1, Fx.item(1, x = 100), 0, 0, 0)
        val b = Fx.placed(2, Fx.item(2, x = 100), 0, 0, 0)
        val top = Fx.placed(3, Fx.item(3, x = 150), 0, 100, 0)
        val detail = Fx.judged(listOf(a, b, top), Fx.profile()).support.last()
        assertEquals(10_000, detail.supportedAreaMm2)
        assertEquals(15_000, detail.bottomAreaMm2)
        assertEquals(false, detail.meetsPolicy)
    }

    @Test fun two_adjacent_carriers_give_exact_full_support() {
        val a = Fx.placed(1, Fx.item(1, x = 100), 0, 0, 0)
        val b = Fx.placed(2, Fx.item(2, x = 100), 100, 0, 0)
        val top = Fx.placed(3, Fx.item(3, x = 200), 0, 100, 0)
        assertEquals(true, Fx.judged(listOf(a, b, top), Fx.profile()).support.last().meetsPolicy)
    }

    @Test fun CE07_placing_on_non_stackable_is_a_violation() {
        val under = Fx.placed(1, Fx.item(1, stackable = false), 0, 0, 0)
        val top = Fx.placed(2, Fx.item(2), 0, 100, 0)
        val g = Fx.judged(listOf(under, top), Fx.profile())
        val kinds = g.handlingIssues.filter { it.instanceId == Fx.uuid(2) }.map { it.kind }
        assertTrue(HandlingIssueKind.ON_NON_STACKABLE in kinds)
    }

    @Test fun removal_priority_warns_without_changing_handling() {
        val deep = Fx.placed(1, Fx.item(1, removal = 1), 0, 0, 500)
        val blocker = Fx.placed(2, Fx.item(2, removal = 5), 0, 0, 100)
        val g = Fx.judged(listOf(deep, blocker), Fx.profile())
        val removal = g.removal.single { it.instanceId == Fx.uuid(1) }
        assertEquals(RemovalVerdict.BLOCKED_IN_CHECKED_CLASS, removal.verdict)
        assertEquals(listOf(Fx.uuid(2)), removal.blockedBy)
        assertEquals(HandlingStatus.OK, g.handling)
        // The later-removed item in front is not blocked by the earlier-removed one behind it.
        assertEquals(RemovalVerdict.CLEAR_IN_CHECKED_CLASS, g.removal.single { it.instanceId == Fx.uuid(2) }.verdict)
        val doubtful = Fx.judged(listOf(Fx.placed(1, Fx.item(1, e = 1, removal = 1), 0, 0, 500), Fx.placed(2, Fx.item(2, e = 1, removal = 5), 100, 0, 100)), Fx.profile())
        assertEquals(RemovalVerdict.ERROR_DECIDES, doubtful.removal.single { it.instanceId == Fx.uuid(1) }.verdict)
    }
}
