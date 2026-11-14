package ai.cargo.geometry

/**
 * Item proxy boxes at a pose under an error bound.
 *
 * The pose anchors the normalized min corner. For a single proxy (offset 0) this is exactly
 * worst=[p, p+d+e) and best=[p, p+max(1,d-e)). Interior proxies of a composite shape may also
 * shift by the error, so their worst bound grows downward and their best bound shrinks from below.
 * This per-proxy treatment is a conservative approximation of the true composite shape. It models
 * dimension error measured from the floor anchor, not position error or total vehicle tolerance.
 * The margin widens worst bounds only, and never below the item's resting face.
 */
fun OrientedShape.boxesAt(p: Vec, bound: Bound, errorMm: Long, marginMm: Long = 0): List<Box> =
    boxes.map { box ->
        when (bound) {
            Bound.NOMINAL -> Box(
                p.x + box.minX, p.y + box.minY, p.z + box.minZ,
                p.x + box.maxX, p.y + box.maxY, p.z + box.maxZ,
            )
            Bound.WORST -> Box(
                p.x + maxOf(0, box.minX - errorMm) - marginMm,
                p.y + maxOf(0, box.minY - errorMm),
                p.z + maxOf(0, box.minZ - errorMm) - marginMm,
                p.x + box.maxX + errorMm + marginMm,
                p.y + box.maxY + errorMm + marginMm,
                p.z + box.maxZ + errorMm + marginMm,
            )
            Bound.BEST -> {
                val (minX, maxX) = bestInterval(box.minX, box.maxX, errorMm)
                val (minY, maxY) = bestInterval(box.minY, box.maxY, errorMm)
                val (minZ, maxZ) = bestInterval(box.minZ, box.maxZ, errorMm)
                Box(p.x + minX, p.y + minY, p.z + minZ, p.x + maxX, p.y + maxY, p.z + maxZ)
            }
        }
    }

/** The anchored face stays put; any other face shrinks. At least 1mm remains so a real item never vanishes. */
private fun bestInterval(min: Long, max: Long, errorMm: Long): Pair<Long, Long> {
    val low = if (min > 0) min + errorMm else 0L
    val high = max - errorMm
    return low to if (high > low) high else low + 1
}

/** Geometry of one placed instance after validation. */
class PlacedGeometry(
    val input: PlacedInput,
    val item: ItemGeometry,
    val shape: OrientedShape,
) {
    val id: String get() = input.instanceId
    val pose: Vec get() = input.poseMm.let { Vec(it.x, it.y, it.z) }

    fun boxes(bound: Bound, marginMm: Long = 0): List<Box> = shape.boxesAt(pose, bound, item.errorMm, marginMm)

    companion object {
        fun of(input: PlacedInput, config: GeometryConfig): PlacedGeometry {
            val limit = config.coordinateAbsMaxMm
            val p = input.poseMm
            if (kotlin.math.abs(p.x) > limit || kotlin.math.abs(p.y) > limit || kotlin.math.abs(p.z) > limit) {
                throw NotAvailableException(NotAvailableReason.POSE_INVALID, "Pose of ${input.instanceId} out of range")
            }
            if (!Orientations.isValidIndex(input.orientationIndex)) {
                throw NotAvailableException(NotAvailableReason.ORIENTATION_INVALID, "Orientation of ${input.instanceId} out of range")
            }
            val geometry = ItemGeometry.of(input.item, config)
            return PlacedGeometry(input, geometry, geometry.oriented(input.orientationIndex.toInt()))
        }
    }
}

/** The whole item inside the space. Sweeps may extend below z=0, where the opening check applies instead. */
internal fun Box.isInside(space: Box, allowOutsideBehindOpening: Boolean = false): Boolean =
    !space.isEmpty &&
        minX >= space.minX && maxX <= space.maxX &&
        minY >= space.minY && maxY <= space.maxY &&
        (allowOutsideBehindOpening || minZ >= space.minZ) && maxZ <= space.maxZ
