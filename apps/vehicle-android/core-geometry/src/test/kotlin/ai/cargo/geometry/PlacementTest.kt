package ai.cargo.geometry

import ai.cargo.contracts.PlacementStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTest {
    private val p = Fx.profile()

    private fun twoBoxes(gapZ: Long, e: Long = 0, es: Long = 0) = Fx.judged(
        listOf(
            Fx.placed(1, Fx.item(1, e = e), 0, 0, 0),
            Fx.placed(2, Fx.item(2, e = e), 0, 0, 100 + gapZ),
        ),
        Fx.profile(es = es),
    )

    @Test fun CE01_face_contact_0mm_is_not_overlap() {
        val g = twoBoxes(0)
        assertEquals(PlacementStatus.CLEAR, g.placement.status)
        assertEquals(0, g.placement.minimumClearanceMm)
        assertFalse(Box(0, 0, 0, 1, 1, 1).overlaps(Box(1, 0, 0, 2, 1, 1)))
    }

    @Test fun CE01_overlap_1mm_is_conflict() {
        val g = twoBoxes(-1)
        assertEquals(PlacementStatus.CONFLICT, g.placement.status)
        assertEquals(-1, g.placement.minimumClearanceMm)
        assertEquals(CollisionKind.INSTANCE, g.placement.issues.single().kind)
    }

    @Test fun CE01_boundary_exceed_1mm_is_conflict_and_exact_fit_clear() {
        val exact = Fx.judged(listOf(Fx.placed(1, Fx.item(1, x = 1000), 0, 0, 0)), p)
        assertEquals(PlacementStatus.CLEAR, exact.placement.status)
        val over = Fx.judged(listOf(Fx.placed(1, Fx.item(1, x = 1001), 0, 0, 0)), p)
        assertEquals(PlacementStatus.CONFLICT, over.placement.status)
        assertEquals(CollisionKind.OUT_OF_BOUNDS, over.placement.issues.single().kind)
        assertEquals(-1, over.placement.minimumClearanceMm)
        val negative = Fx.judged(listOf(Fx.placed(1, Fx.item(1), -1, 0, 0)), p)
        assertEquals(PlacementStatus.CONFLICT, negative.placement.status)
    }

    @Test fun CE02_item_error_flipping_the_result_is_uncertain() {
        // Nominal gap 1mm: clear at nominal, colliding at worst, clear at best.
        assertEquals(PlacementStatus.UNCERTAIN, twoBoxes(1, e = 2).placement.status)
        // With zero error the same gap is decided.
        assertEquals(PlacementStatus.CLEAR, twoBoxes(1, e = 0).placement.status)
        // A nominal 1mm overlap that error can remove is also doubtful, not conflict.
        assertEquals(PlacementStatus.UNCERTAIN, twoBoxes(-1, e = 2).placement.status)
        // A deep overlap conflicts for every value within error.
        assertEquals(PlacementStatus.CONFLICT, twoBoxes(-50, e = 2).placement.status)
    }

    @Test fun CE02_space_error_flipping_the_result_is_uncertain() {
        val fit = listOf(Fx.placed(1, Fx.item(1, x = 1000), 0, 0, 0))
        assertEquals(PlacementStatus.UNCERTAIN, Fx.judged(fit, Fx.profile(es = 5)).placement.status)
        val over = listOf(Fx.placed(1, Fx.item(1, x = 1004), 0, 0, 0))
        assertEquals(PlacementStatus.UNCERTAIN, Fx.judged(over, Fx.profile(es = 5)).placement.status)
        val far = listOf(Fx.placed(1, Fx.item(1, x = 1006), 0, 0, 0))
        assertEquals(PlacementStatus.CONFLICT, Fx.judged(far, Fx.profile(es = 5)).placement.status)
    }

    @Test fun CE02_obstacle_error_bounds() {
        val thin = Fx.profile(es = 5, obstacles = listOf(Fx.obstacle(0, 0, 200, 100, 100, 8)))
        // A thin obstacle vanishes at best bounds, so a collision with it is doubtful.
        val hit = Fx.judged(listOf(Fx.placed(1, Fx.item(1), 0, 0, 150)), thin)
        assertEquals(PlacementStatus.UNCERTAIN, hit.placement.status)
        val thick = Fx.profile(es = 5, obstacles = listOf(Fx.obstacle(0, 0, 200, 100, 100, 50)))
        assertEquals(PlacementStatus.CONFLICT, Fx.judged(listOf(Fx.placed(1, Fx.item(1), 0, 0, 150)), thick).placement.status)
    }

    /**
     * RV-F03 / F-03 counterexample: profile error 10mm, item error 5mm, the same nominal 3mm gap to each wall.
     * Every measured wall must give the same doubtful result; before the fix the right wall and the opening read CLEAR.
     */
    @Test fun RV_F03_same_gap_reads_the_same_against_every_measured_wall() {
        val p = Fx.profile(x = 1000, y = 1000, z = 1000, es = 10)
        val item = Fx.item(1, x = 100, y = 100, z = 100, e = 5)
        val far = 400L
        val cases = mapOf(
            "right wall x=0" to Vec(3, 0, far),
            "left wall" to Vec(1000 - 100 - 3, 0, far),
            "opening z=0" to Vec(far, 0, 3),
            "front wall" to Vec(far, 0, 1000 - 100 - 3),
            "ceiling" to Vec(far, 1000 - 100 - 3, far),
        )
        for ((wall, pose) in cases) {
            val g = Fx.judged(listOf(Fx.placed(1, item, pose.x, pose.y, pose.z)), p)
            assertEquals(PlacementStatus.UNCERTAIN, g.placement.status, wall)
            assertEquals(CollisionKind.OUT_OF_BOUNDS, g.placement.issues.single().kind, wall)
        }
        // Resting on the floor is the reference, not a gap: far from every wall the item is clear.
        assertEquals(PlacementStatus.CLEAR, Fx.judged(listOf(Fx.placed(1, item, far, 0, far)), p).placement.status)
        // A gap wider than both errors is clear at the vehicle right wall and the opening as well.
        assertEquals(PlacementStatus.CLEAR, Fx.judged(listOf(Fx.placed(1, item, 11, 0, 11)), p).placement.status)
        // Penetrating beyond the best wall is a conflict on the origin side too.
        assertEquals(PlacementStatus.CONFLICT, Fx.judged(listOf(Fx.placed(1, item, -11, 0, far)), p).placement.status)
        assertEquals(PlacementStatus.UNCERTAIN, Fx.judged(listOf(Fx.placed(1, item, -10, 0, far)), p).placement.status)
        // Zero profile error keeps exact face contact clear, as before.
        assertEquals(PlacementStatus.CLEAR, Fx.judged(listOf(Fx.placed(1, Fx.item(1), 0, 0, 0)), Fx.profile()).placement.status)
    }

    @Test fun flush_contact_with_error_can_be_uncertain() {
        assertEquals(PlacementStatus.UNCERTAIN, twoBoxes(0, e = 1).placement.status)
    }

    @Test fun best_item_never_vanishes_below_1mm() {
        val shape = ItemGeometry.of(Fx.item(1, x = 3, y = 3, z = 3, e = 50), GeometryConfig.V1).oriented(0)
        val best = shape.boxesAt(Vec(0, 0, 0), Bound.BEST, 50).single()
        assertEquals(Box(0, 0, 0, 1, 1, 1), best)
        assertEquals(Box(0, 0, 0, 53, 53, 53), shape.boxesAt(Vec(0, 0, 0), Bound.WORST, 50).single())
    }

    @Test fun composite_worst_contains_nominal_and_nominal_contains_best() {
        val item = Fx.lItem(e = 3)
        val shape = ItemGeometry.of(item, GeometryConfig.V1).oriented(0)
        val p = Vec(10, 0, 20)
        val nominal = shape.boxesAt(p, Bound.NOMINAL, 3)
        val worst = shape.boxesAt(p, Bound.WORST, 3)
        val best = shape.boxesAt(p, Bound.BEST, 3)
        nominal.indices.forEach { i ->
            assertTrue(worst[i].minX <= nominal[i].minX && worst[i].maxX >= nominal[i].maxX, "worst covers nominal")
            assertTrue(best[i].minX >= nominal[i].minX && best[i].maxX <= nominal[i].maxX, "best inside nominal")
        }
    }

    @Test fun unsupported_profile_is_not_available_not_a_result() {
        val r = SceneJudge.judge(listOf(Fx.placed(1, Fx.item(), 0, 0, 0)), Fx.profile(grade = ai.cargo.contracts.ProfileGrade.UNSUPPORTED), emptyList())
        assertEquals(NotAvailableReason.PROFILE_UNSUPPORTED, (r as SceneJudgement.NotAvailable).reason)
    }

    @Test fun unconfirmed_dimensions_are_not_judged() {
        val r = SceneJudge.judge(listOf(Fx.placed(1, Fx.item(confirmed = false), 0, 0, 0)), p, emptyList())
        assertEquals(NotAvailableReason.DIMENSIONS_UNCONFIRMED, (r as SceneJudgement.NotAvailable).reason)
    }

    @Test fun malformed_inputs_are_typed() {
        val mismatched = Fx.item(1, x = 100).copy(measuredDimensionsMm = ai.cargo.contracts.DimensionsMm(101, 100, 100))
        assertEquals(NotAvailableReason.ITEM_INVALID, (SceneJudge.judge(listOf(Fx.placed(1, mismatched, 0, 0, 0)), p, emptyList()) as SceneJudgement.NotAvailable).reason)
        assertEquals(NotAvailableReason.POSE_INVALID, (SceneJudge.judge(listOf(Fx.placed(1, Fx.item(), 10_001, 0, 0)), p, emptyList()) as SceneJudgement.NotAvailable).reason)
        assertEquals(NotAvailableReason.ORIENTATION_INVALID, (SceneJudge.judge(listOf(Fx.placed(1, Fx.item(), 0, 0, 0, 24)), p, emptyList()) as SceneJudgement.NotAvailable).reason)
        val tooMany = (1..101).map { Fx.placed(it, Fx.item(), 0, 0, 0) }
        assertEquals(NotAvailableReason.INSTANCE_LIMIT_EXCEEDED, (SceneJudge.judge(tooMany, p, emptyList()) as SceneJudgement.NotAvailable).reason)
    }

    @Test fun extreme_inputs_do_not_overflow() {
        val big = Fx.item(1, x = 10_000, y = 10_000, z = 10_000, e = 1000)
        val g = Fx.judged(listOf(Fx.placed(1, big, -10_000, 0, 10_000)), Fx.profile(10_000, 10_000, 10_000, es = 1000))
        assertEquals(PlacementStatus.CONFLICT, g.placement.status)
        assertTrue(g.errorMm <= 1000 && g.placement.minimumClearanceMm >= -10_000)
        assertTrue(g.placement.issues.all { it.location.x >= -10_000 && it.location.z <= 10_000 })
    }

    @Test fun boundary_values_generated_against_integer_oracle() {
        val rnd = java.util.Random(4242)
        // Only x varies; z sits clear of both z walls so it never decides the result.
        repeat(3000) {
            val e = rnd.nextInt(4).toLong()
            val es = rnd.nextInt(4).toLong()
            val d = 5L + rnd.nextInt(5)
            val pose = rnd.nextInt(30).toLong() - 5
            val size = 20L
            val g = Fx.judged(listOf(Fx.placed(1, Fx.item(1, x = d, y = 1, z = 1, e = e), pose, 0, 20)), Fx.profile(x = size, y = 50, z = 50, es = es))
            val worst = pose >= es && pose + d + e <= size - es
            val best = pose >= -es && pose + maxOf(1, d - e) <= size + es
            val expected = when { worst -> PlacementStatus.CLEAR; best -> PlacementStatus.UNCERTAIN; else -> PlacementStatus.CONFLICT }
            assertEquals(expected, g.placement.status, "pose=$pose d=$d e=$e es=$es")
        }
    }
}
