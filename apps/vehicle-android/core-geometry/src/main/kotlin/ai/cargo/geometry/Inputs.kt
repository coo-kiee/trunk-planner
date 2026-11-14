package ai.cargo.geometry

import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm

/** A judgement cannot be produced at all. This is never a pass, a fail or an impossibility claim. */
enum class NotAvailableReason {
    PROFILE_UNSUPPORTED,
    PROFILE_INVALID,
    DIMENSIONS_UNCONFIRMED,
    ITEM_INVALID,
    ITEM_VERSION_MISSING,
    INSTANCE_LIMIT_EXCEEDED,
    POSE_INVALID,
    ORIENTATION_INVALID,
}

class NotAvailableException(val reason: NotAvailableReason, val detail: String) : Exception(detail)

private fun fail(reason: NotAvailableReason, detail: String): Nothing =
    throw NotAvailableException(reason, detail)

/** One placed instance as geometry sees it. Render meshes have no representation in this input type. */
data class PlacedInput(
    val instanceId: String,
    val item: ItemVersion,
    val poseMm: Vec3Mm,
    val orientationIndex: Long,
)

/** Oriented decision proxies relative to the normalized min corner. */
class OrientedShape(val orientationIndex: Int, val extent: Vec, val boxes: List<Box>)

/** Validated item ready for rotation; proxy boxes are decision geometry, never decorative mesh. */
class ItemGeometry private constructor(val item: ItemVersion, private val proxies: List<Box>) {
    val errorMm: Long get() = item.errorMm
    val isSingleBox: Boolean get() = item.shapeKind == ShapeKind.BOX && proxies.size == 1
    val measuredExtent: Vec
        get() = item.measuredDimensionsMm.let { Vec(it.x, it.y, it.z) }

    fun oriented(index: Int): OrientedShape {
        val corners = proxies.map { box ->
            val a = Orientations.rotate(index, Vec(box.minX, box.minY, box.minZ))
            val b = Orientations.rotate(index, Vec(box.maxX, box.maxY, box.maxZ))
            Box(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        }
        val base = Vec(corners.minOf { it.minX }, corners.minOf { it.minY }, corners.minOf { it.minZ })
        val normalized = corners.map {
            Box(it.minX - base.x, it.minY - base.y, it.minZ - base.z, it.maxX - base.x, it.maxY - base.y, it.maxZ - base.z)
        }
        val extent = Vec(normalized.maxOf { it.maxX }, normalized.maxOf { it.maxY }, normalized.maxOf { it.maxZ })
        return OrientedShape(index, extent, normalized)
    }

    fun allowedOrientations(): List<Int> = Orientations.allowed(item.rotationRule, measuredExtent)

    companion object {
        fun of(item: ItemVersion, config: GeometryConfig): ItemGeometry {
            if (!item.confirmedDimensions) {
                fail(NotAvailableReason.DIMENSIONS_UNCONFIRMED, "User has not confirmed dimensions of ${item.id}")
            }
            requireDimension(item.measuredDimensionsMm, config, "measured")
            if (item.errorMm !in 0..GeometryConfig.SNAPSHOT_ERROR_MAX_MM) invalidItem("errorMm out of range")
            val count = item.shapeProxy.size
            if (count !in 1..config.maxProxyBoxesPerItem) invalidItem("proxy count $count")
            when (item.shapeKind) {
                ShapeKind.BOX, ShapeKind.CYLINDER_BOUNDING_BOX -> if (count != 1) invalidItem("${item.shapeKind} needs one proxy")
                ShapeKind.L_SHAPE -> if (count < 2) invalidItem("L_SHAPE needs at least two proxies")
            }
            val boxes = item.shapeProxy.map { proxy ->
                requireDimension(proxy.size, config, "proxy")
                val o = proxy.origin
                if (o.x < 0 || o.y < 0 || o.z < 0) invalidItem("proxy origin must be inside the item frame")
                Box(o.x, o.y, o.z, o.x + proxy.size.x, o.y + proxy.size.y, o.z + proxy.size.z)
            }
            val measured = item.measuredDimensionsMm
            val covers = boxes.minOf { it.minX } == 0L && boxes.minOf { it.minY } == 0L && boxes.minOf { it.minZ } == 0L &&
                boxes.maxOf { it.maxX } == measured.x && boxes.maxOf { it.maxY } == measured.y && boxes.maxOf { it.maxZ } == measured.z
            if (!covers) invalidItem("proxy bounding box differs from confirmed dimensions")
            return ItemGeometry(item, boxes)
        }

        private fun requireDimension(d: DimensionsMm, config: GeometryConfig, label: String) {
            val range = 1..config.inputDimensionMaxMm
            if (d.x !in range || d.y !in range || d.z !in range) invalidItem("$label dimension out of range")
        }

        private fun invalidItem(detail: String): Nothing = fail(NotAvailableReason.ITEM_INVALID, detail)
    }
}

/** Opening rectangle in the z=0 plane; y starts at the floor. */
data class OpeningWindow(val minX: Long, val maxX: Long, val maxY: Long) {
    /** A cross-section of this width and height fits somewhere in the window. */
    fun admits(width: Long, height: Long): Boolean = width <= maxX - minX && height <= maxY

    /** This box's x/y footprint lies inside the window. */
    fun contains(box: Box): Boolean = box.minX >= minX && box.maxX <= maxX && box.minY >= 0 && box.maxY <= maxY
}

/** Validated cargo space. An UNSUPPORTED profile has no space and therefore no judgement. */
class ProfileSpace private constructor(
    val profile: ProfileVersion,
    val bounds: Vec,
    private val obstacles: List<Box>,
    val errorMm: Long,
) {
    val opening: Vec? get() = profile.opening?.let { Vec(it.x, it.y, it.z) }

    /**
     * Every measured wall may sit up to errorMm either way: the vehicle-right wall (x=0), the opening (z=0), the
     * vehicle-left wall, the front wall and the ceiling. Worst pulls all of them inward, best pushes them out, so a
     * close gap reads the same against every wall. The floor is the plane items rest on and heights are measured
     * from it, so it stays at y=0 and only the ceiling moves.
     */
    fun space(bound: Bound): Box {
        val delta = signedError(bound)
        return Box(-delta, 0, -delta, bounds.x + delta, bounds.y + delta, bounds.z + delta)
    }

    /**
     * The opening rectangle at a bound: its two side edges carry the same two-sided error as the walls, its bottom is
     * the floor reference and only its top moves. Opening dimension fit and the route cross-section both use this, so
     * the two can never disagree about the same opening. Null when the opening was not measured.
     */
    fun openingWindow(bound: Bound): OpeningWindow? = opening?.let {
        val delta = signedError(bound)
        OpeningWindow(minX = -delta, maxX = it.x + delta, maxY = it.y + delta)
    }

    /** Negative for worst (walls inward), positive for best (walls outward). */
    fun signedError(bound: Bound): Long = when (bound) { Bound.WORST -> -errorMm; Bound.NOMINAL -> 0L; Bound.BEST -> errorMm }

    /** Best-bound obstacles with an empty axis disappear: they cannot be proved to block anything. */
    fun obstacles(bound: Bound): List<Box> = when (bound) {
        Bound.NOMINAL -> obstacles
        Bound.WORST -> obstacles.map {
            Box(it.minX - errorMm, it.minY - errorMm, it.minZ - errorMm, it.maxX + errorMm, it.maxY + errorMm, it.maxZ + errorMm)
        }
        Bound.BEST -> obstacles.map {
            Box(it.minX + errorMm, it.minY + errorMm, it.minZ + errorMm, it.maxX - errorMm, it.maxY - errorMm, it.maxZ - errorMm)
        }.filterNot { it.isEmpty }
    }

    companion object {
        fun of(profile: ProfileVersion, config: GeometryConfig): ProfileSpace {
            if (profile.grade == ProfileGrade.UNSUPPORTED) {
                fail(NotAvailableReason.PROFILE_UNSUPPORTED, "Profile ${profile.id} has no authoritative or measured bounds")
            }
            val bounds = profile.bounds ?: fail(NotAvailableReason.PROFILE_INVALID, "Supported grade without bounds")
            val range = 1..config.inputDimensionMaxMm
            if (bounds.x !in range || bounds.y !in range || bounds.z !in range) {
                fail(NotAvailableReason.PROFILE_INVALID, "bounds out of range")
            }
            if (profile.errorMm !in 0..GeometryConfig.SNAPSHOT_ERROR_MAX_MM) {
                fail(NotAvailableReason.PROFILE_INVALID, "errorMm out of range")
            }
            val boxes = profile.obstacles.map { proxy ->
                val o = proxy.origin
                val s = proxy.size
                val limit = config.coordinateAbsMaxMm
                if (kotlin.math.abs(o.x) > limit || kotlin.math.abs(o.y) > limit || kotlin.math.abs(o.z) > limit ||
                    s.x !in range || s.y !in range || s.z !in range
                ) fail(NotAvailableReason.PROFILE_INVALID, "obstacle out of range")
                Box(o.x, o.y, o.z, o.x + s.x, o.y + s.y, o.z + s.z)
            }
            return ProfileSpace(profile, Vec(bounds.x, bounds.y, bounds.z), boxes, profile.errorMm)
        }
    }
}
