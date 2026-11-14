package ai.cargo.geometry

import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.OpeningStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PathSweepTest {
    private val opening = DimensionsMm(1000, 1000, 50)

    private fun path(p: ai.cargo.contracts.ProfileVersion, item: ai.cargo.contracts.ItemVersion = Fx.item(), others: List<PlacedInput> = emptyList(), x: Long = 0, y: Long = 0, z: Long = 800) =
        Fx.judged(others + Fx.placed(50, item, x, y, z), p, listOf(Fx.uuid(50)))

    @Test fun straight_z_route_is_verified_with_its_named_class() {
        val g = path(Fx.profile(opening = opening))
        assertEquals(PathVerdict.VERIFIED_IN_MODEL, g.path)
    }

    @Test fun RV_F03_route_needs_clearance_from_the_opening_edges_by_the_profile_error() {
        val p = Fx.profile(es = 10, opening = DimensionsMm(300, 1000, 50))
        // Opening wide enough for the item plus the error band on both sides: verified, entering away from x=0.
        assertEquals(PathVerdict.VERIFIED_IN_MODEL, path(p, Fx.item(e = 5), x = 300, z = 800).path)
        // Only 3mm spare on each side of a 294mm opening: error decides the route, so it is not verified.
        val narrow = Fx.profile(es = 10, opening = DimensionsMm(106, 1000, 50))
        assertEquals(PathVerdict.UNVERIFIED, path(narrow, Fx.item(e = 0), x = 400, z = 800).path)
    }

    @Test fun thin_obstacle_cannot_be_tunnelled_through() {
        // A wall 1mm thin spanning the whole section in the middle of the route: sampling at 100mm steps would miss it.
        val wall = Fx.obstacle(0, 0, 400, 1000, 1000, 1)
        val g = path(Fx.profile(opening = opening, obstacles = listOf(wall)))
        assertEquals(OpeningStatus.FITS, g.opening)
        assertEquals(PathVerdict.NOT_FOUND_IN_CHECKED_CLASS, g.path)
    }

    @Test fun entry_can_go_around_a_partial_obstacle_then_translate() {
        // Obstacle blocks the column directly in front of the target; the route enters beside it and slides over.
        val block = Fx.obstacle(0, 0, 400, 300, 1000, 100)
        val g = path(Fx.profile(opening = opening, obstacles = listOf(block)))
        assertEquals(PathVerdict.VERIFIED_IN_MODEL, g.path)
    }

    @Test fun lateral_leg_is_also_swept() {
        // The opening is narrow, so the item enters near x=0 and must slide to x=800, where a wall stands in between.
        val narrow = DimensionsMm(200, 1000, 50)
        val wall = Fx.obstacle(500, 0, 700, 10, 1000, 300)
        val g = path(Fx.profile(opening = narrow, obstacles = listOf(wall)), x = 800, z = 800)
        assertEquals(PathVerdict.NOT_FOUND_IN_CHECKED_CLASS, g.path)
        val clearProfile = Fx.profile(opening = narrow)
        assertEquals(PathVerdict.VERIFIED_IN_MODEL, path(clearProfile, x = 800, z = 800).path)
    }

    @Test fun other_items_block_routes_but_not_yet_loaded_subjects_do_not() {
        val front = Fx.placed(1, Fx.item(1, x = 1000, y = 1000, z = 100), 0, 0, 100)
        assertEquals(PathVerdict.NOT_FOUND_IN_CHECKED_CLASS, path(Fx.profile(opening = opening), others = listOf(front)).path)
        val g = Fx.judged(listOf(Fx.placed(50, Fx.item(), 0, 0, 800), front), Fx.profile(opening = opening), listOf(Fx.uuid(50), Fx.uuid(1)))
        assertEquals(PathVerdict.VERIFIED_IN_MODEL, g.pathDetails.first().verdict)
        assertEquals(PathVerdict.VERIFIED_IN_MODEL, g.pathDetails.last().verdict)
    }

    @Test fun error_deciding_the_route_stays_unverified() {
        // Passage exactly as wide as the item at nominal: colliding at worst, free at best.
        val walls = listOf(Fx.obstacle(100, 0, 300, 900, 1000, 10), Fx.obstacle(0, 100, 300, 100, 900, 10))
        val g = path(Fx.profile(opening = opening, obstacles = walls), Fx.item(e = 1))
        assertEquals(PathVerdict.UNVERIFIED, g.path)
        assertEquals(PathNote.ERROR_DECIDES, g.pathDetails.single().note)
    }

    @Test fun item_wider_than_opening_in_this_orientation_is_not_found_even_if_rotation_would_fit() {
        val long = Fx.item(1, x = 500, y = 100, z = 100)
        val narrow = DimensionsMm(200, 1000, 50)
        val g = path(Fx.profile(opening = narrow), long)
        assertEquals(OpeningStatus.FITS, g.opening, "another permitted orientation fits the opening")
        assertEquals(PathVerdict.NOT_FOUND_IN_CHECKED_CLASS, g.path, "the route class has no rotation in motion")
    }

    @Test fun exhausted_path_check_limit_is_unverified_never_not_found() {
        val wall = Fx.obstacle(0, 0, 400, 1000, 1000, 1)
        val p = Fx.profile(opening = opening, obstacles = listOf(wall))
        val r = SceneJudge.judge(listOf(Fx.placed(50, Fx.item(), 0, 0, 800)), p, listOf(Fx.uuid(50)), maxPathChecks = 0)
        val g = (r as SceneJudgement.Judged).geometry
        assertEquals(PathVerdict.UNVERIFIED, g.path)
        assertEquals(PathNote.CHECK_LIMIT_REACHED, g.pathDetails.single().note)
    }
}
