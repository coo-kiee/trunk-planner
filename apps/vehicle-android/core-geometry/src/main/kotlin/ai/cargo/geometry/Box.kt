package ai.cargo.geometry

/** Half-open integer millimetre AABB: [min, max) on every axis. A touching face is not an overlap. */
data class Box(
    val minX: Long, val minY: Long, val minZ: Long,
    val maxX: Long, val maxY: Long, val maxZ: Long,
) {
    val isEmpty: Boolean get() = minX >= maxX || minY >= maxY || minZ >= maxZ

    fun overlaps(other: Box): Boolean =
        !isEmpty && !other.isEmpty &&
            minX < other.maxX && other.minX < maxX &&
            minY < other.maxY && other.minY < maxY &&
            minZ < other.maxZ && other.minZ < maxZ

    fun intersection(other: Box): Box? {
        val box = Box(
            maxOf(minX, other.minX), maxOf(minY, other.minY), maxOf(minZ, other.minZ),
            minOf(maxX, other.maxX), minOf(maxY, other.maxY), minOf(maxZ, other.maxZ),
        )
        return box.takeUnless { it.isEmpty }
    }

    /** Smallest box covering both; the exact swept volume of an axis-aligned translation. */
    fun hull(other: Box): Box = Box(
        minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ),
        maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ),
    )

    /**
     * Signed separation: 0 for a touching face, positive for a gap, negative for penetration.
     * Each axis gap is exact; the result is the largest axis gap.
     */
    fun separation(other: Box): Long = maxOf(
        maxOf(minX - other.maxX, other.minX - maxX),
        maxOf(minY - other.maxY, other.minY - maxY),
        maxOf(minZ - other.maxZ, other.minZ - maxZ),
    )
}

/** Integer offset used for poses and oriented extents. */
data class Vec(val x: Long, val y: Long, val z: Long)

/** Which bound of the measured-error interval a geometric query uses. */
enum class Bound {
    /** Largest item and obstacles, smallest space: passing here passes for every value within error. */
    WORST,

    /** Measured values, used for support contact and clearance reporting. */
    NOMINAL,

    /** Smallest item and obstacles, largest space: failing here fails for every value within error. */
    BEST,
}

/** Rectangle on the floor plane (x, z) used for the projected support area. */
internal data class FloorRect(val minX: Long, val minZ: Long, val maxX: Long, val maxZ: Long) {
    val isEmpty: Boolean get() = minX >= maxX || minZ >= maxZ

    fun intersect(other: FloorRect): FloorRect = FloorRect(
        maxOf(minX, other.minX), maxOf(minZ, other.minZ),
        minOf(maxX, other.maxX), minOf(maxZ, other.maxZ),
    )
}

/** Exact area of a union of rectangles by slab sweep, so overlapping supports are never counted twice. */
internal fun unionArea(rects: List<FloorRect>): Long {
    val live = rects.filterNot { it.isEmpty }
    val slabs = live.flatMap { listOf(it.minX, it.maxX) }.distinct().sorted()
    var area = 0L
    for (index in 0 until slabs.size - 1) {
        val left = slabs[index]
        val right = slabs[index + 1]
        val covering = live.filter { it.minX <= left && right <= it.maxX }.sortedBy { it.minZ }
        var covered = 0L
        var reach = Long.MIN_VALUE
        for (rect in covering) {
            val start = maxOf(rect.minZ, reach)
            if (rect.maxZ > start) covered = Math.addExact(covered, rect.maxZ - start)
            reach = maxOf(reach, rect.maxZ)
        }
        area = Math.addExact(area, Math.multiplyExact(right - left, covered))
    }
    return area
}
