package ai.cargo.geometry

/** Why a route is unverified; never rendered as "does not pass". */
enum class PathNote { NONE, NOT_RUN, OPENING_UNMEASURED, ERROR_DECIDES, CHECK_LIMIT_REACHED }

enum class PathVerdict { VERIFIED_IN_MODEL, NOT_FOUND_IN_CHECKED_CLASS, UNVERIFIED }

data class PathDetail(val instanceId: String?, val verdict: PathVerdict, val note: PathNote, val pairChecks: Long)

/**
 * Z_THEN_XY_AABB_SWEEP_V1: translate along +z from outside the opening at an entry (x, y), then along x and y
 * in either order to the pose. A hull of the moved box is the exact swept volume of one axis translation, so a
 * thin obstacle cannot be tunnelled through. No rotation in motion, curved route or tailgate motion is covered,
 * and failing this class is not proof that no route exists.
 */
internal class PathSweep(
    private val space: ProfileSpace,
    private val config: GeometryConfig,
    private val maxPairChecks: Long,
) {
    private var checks = 0L

    fun check(subject: PlacedGeometry, blockers: List<PlacedGeometry>): PathDetail {
        val opening = space.opening
            ?: return PathDetail(subject.id, PathVerdict.UNVERIFIED, PathNote.OPENING_UNMEASURED, 0)
        val routes = routes(subject, blockers, opening)
        val outcome = try {
            when {
                routes.any { routeClear(subject, it, blockers, Bound.WORST) } -> PathVerdict.VERIFIED_IN_MODEL
                routes.any { routeClear(subject, it, blockers, Bound.BEST) } -> PathVerdict.UNVERIFIED
                else -> PathVerdict.NOT_FOUND_IN_CHECKED_CLASS
            }
        } catch (_: CheckLimit) {
            return PathDetail(subject.id, PathVerdict.UNVERIFIED, PathNote.CHECK_LIMIT_REACHED, checks)
        }
        val note = if (outcome == PathVerdict.UNVERIFIED) PathNote.ERROR_DECIDES else PathNote.NONE
        return PathDetail(subject.id, outcome, note, checks)
    }

    private class CheckLimit : Exception()

    private data class Route(val entryX: Long, val entryY: Long, val xFirst: Boolean)

    /** Entries are the pose clamped to the opening, the opening edges and every blocker face. */
    private fun routes(subject: PlacedGeometry, blockers: List<PlacedGeometry>, opening: Vec): List<Route> {
        val extent = subject.shape.extent
        val pose = subject.pose
        val faces = blockers.flatMap { it.boxes(Bound.NOMINAL) } + space.obstacles(Bound.NOMINAL)
        val e = subject.item.errorMm
        val es = space.errorMm
        // Entries just inside the worst and best opening edges, so error alone never hides an existing route.
        val edges = listOf(es + config.marginMm, opening.x - es - extent.x - e - 2 * config.marginMm, -es, opening.x + es - extent.x)
        val xs = entryAxis(pose.x, extent.x, opening.x, faces.flatMap { listOf(it.maxX, it.minX - extent.x) } + edges, es)
        val ys = entryAxis(pose.y, extent.y, opening.y, faces.flatMap { listOf(it.maxY, it.minY - extent.y) }, 0)
        return xs.flatMap { x -> ys.flatMap { y -> listOf(Route(x, y, true), Route(x, y, false)) } }
    }

    private fun entryAxis(pose: Long, extent: Long, opening: Long, faces: List<Long>, slack: Long): List<Long> {
        val top = opening - extent
        val preferred = listOf(pose.coerceIn(0, maxOf(0, top)), 0L, maxOf(0, top))
        return (preferred + faces.filter { it in -slack..top + slack }).distinct()
    }

    private fun routeClear(subject: PlacedGeometry, route: Route, blockers: List<PlacedGeometry>, bound: Bound): Boolean {
        val shape = subject.shape
        val e = subject.item.errorMm
        val margin = if (bound == Bound.WORST) config.marginMm else 0L
        val pose = subject.pose
        val startZ = -(shape.extent.z + e + margin + 1)
        val outside = Vec(route.entryX, route.entryY, startZ)
        val entered = Vec(route.entryX, route.entryY, pose.z)
        val corner = if (route.xFirst) Vec(pose.x, route.entryY, pose.z) else Vec(route.entryX, pose.y, pose.z)
        val window = space.openingWindow(bound) ?: return false
        if (!shape.boxesAt(outside, bound, e, margin).all(window::contains)) return false
        val legs = listOf(outside to entered, entered to corner, corner to pose)
        val solid = blockers.flatMap { it.boxes(bound, margin) } + space.obstacles(bound)
        val cargo = space.space(bound)
        for ((from, to) in legs) {
            val a = shape.boxesAt(from, bound, e, margin)
            val b = shape.boxesAt(to, bound, e, margin)
            for (index in a.indices) {
                val swept = a[index].hull(b[index])
                if (!swept.isInside(cargo, allowOutsideBehindOpening = true)) return false
                for (box in solid) {
                    checks = Math.addExact(checks, 1)
                    if (checks > maxPairChecks) throw CheckLimit()
                    if (swept.overlaps(box)) return false
                }
            }
        }
        return true
    }
}
