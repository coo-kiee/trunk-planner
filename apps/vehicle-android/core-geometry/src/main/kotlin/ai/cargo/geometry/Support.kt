package ai.cargo.geometry

/**
 * Provisional geometric support, computed on nominal exact contact. It is not a load, securing or safety result.
 * Only the floor and the top of a stackable item carry; an obstacle top is deliberately not a support surface.
 */
data class SupportDetail(
    val instanceId: String,
    val supportedAreaMm2: Long,
    val bottomAreaMm2: Long,
    val onNonStackable: List<String>,
    val meetsPolicy: Boolean,
)

internal fun evaluateSupport(
    shape: OrientedShape,
    pose: Vec,
    instanceId: String,
    others: List<PlacedGeometry>,
    space: ProfileSpace,
    config: GeometryConfig,
): SupportDetail {
    val bottom = shape.boxes.filter { it.minY == 0L }.map {
        FloorRect(pose.x + it.minX, pose.z + it.minZ, pose.x + it.maxX, pose.z + it.maxZ)
    }
    val carriers = mutableListOf<FloorRect>()
    // The floor reaches the largest space within error: leaving the space is the placement axis's result, not this one's.
    if (pose.y == 0L) space.space(Bound.BEST).let { carriers += FloorRect(it.minX, it.minZ, it.maxX, it.maxZ) }
    val onNonStackable = mutableListOf<String>()
    for (other in others) {
        val tops = other.boxes(Bound.NOMINAL).filter { it.maxY == pose.y }.map { FloorRect(it.minX, it.minZ, it.maxX, it.maxZ) }
        if (tops.isEmpty()) continue
        if (other.item.item.stackable) {
            carriers += tops
        } else if (tops.any { top -> bottom.any { !it.intersect(top).isEmpty } }) {
            onNonStackable += other.id
        }
    }
    val bottomArea = unionArea(bottom)
    val supported = unionArea(bottom.flatMap { rect -> carriers.map { rect.intersect(it) } })
    // Cross multiplication keeps the ratio exact; no floating point enters the decision.
    val meets = Math.multiplyExact(supported, GeometryConfig.BASIS_POINTS_FULL) >=
        Math.multiplyExact(config.supportRatioBasisPoints, bottomArea)
    return SupportDetail(instanceId, supported, bottomArea, onNonStackable, meets)
}
