package ai.cargo.geometry

import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.Vec3Mm

enum class CollisionKind { OUT_OF_BOUNDS, OBSTACLE, INSTANCE }

/** One failed or doubtful pair check; UNCERTAIN means error decides, CONFLICT means no value within error passes. */
data class CollisionIssue(
    val instanceId: String,
    val kind: CollisionKind,
    val otherInstanceId: String?,
    val status: PlacementStatus,
    val location: Vec3Mm,
)

data class PlacementDetail(
    val status: PlacementStatus,
    val issues: List<CollisionIssue>,
    /** Nominal signed distance; 0 is touching. The floor face a resting item stands on is not counted. */
    val minimumClearanceMm: Long,
)

/**
 * Worst bounds all clear gives CLEAR; any collision even at best bounds gives CONFLICT;
 * everything between gives UNCERTAIN, never a rounded CLEAR or CONFLICT.
 */
internal fun judgePlacement(items: List<PlacedGeometry>, space: ProfileSpace, config: GeometryConfig): PlacementDetail {
    val issues = mutableListOf<CollisionIssue>()
    var clearance = Long.MAX_VALUE
    items.forEachIndexed { index, item ->
        boundsIssue(item, space, config)?.let(issues::add)
        issues += obstacleIssues(item, space, config)
        clearance = minOf(clearance, boundsClearance(item, space))
        space.obstacles(Bound.NOMINAL).forEach { obstacle ->
            item.boxes(Bound.NOMINAL).forEach { clearance = minOf(clearance, it.separation(obstacle)) }
        }
        for (other in items.drop(index + 1)) {
            instanceIssue(item, other, config)?.let(issues::add)
            clearance = minOf(clearance, instanceClearance(item, other))
        }
    }
    val status = when {
        issues.any { it.status == PlacementStatus.CONFLICT } -> PlacementStatus.CONFLICT
        issues.isNotEmpty() -> PlacementStatus.UNCERTAIN
        else -> PlacementStatus.CLEAR
    }
    val limit = config.coordinateAbsMaxMm
    val reported = if (clearance == Long.MAX_VALUE) 0L else clearance.coerceIn(-limit, limit)
    return PlacementDetail(status, issues, reported)
}

private fun boundsIssue(item: PlacedGeometry, space: ProfileSpace, config: GeometryConfig): CollisionIssue? {
    val worstInside = item.boxes(Bound.WORST, config.marginMm).all { it.isInside(space.space(Bound.WORST)) }
    if (worstInside) return null
    val bestInside = item.boxes(Bound.BEST).all { it.isInside(space.space(Bound.BEST)) }
    val status = if (bestInside) PlacementStatus.UNCERTAIN else PlacementStatus.CONFLICT
    return CollisionIssue(item.id, CollisionKind.OUT_OF_BOUNDS, null, status, location(item.boxes(Bound.NOMINAL).first(), config))
}

private fun obstacleIssues(item: PlacedGeometry, space: ProfileSpace, config: GeometryConfig): List<CollisionIssue> {
    val worstBoxes = item.boxes(Bound.WORST, config.marginMm)
    val bestBoxes = item.boxes(Bound.BEST)
    val issues = mutableListOf<CollisionIssue>()
    for (obstacle in space.obstacles(Bound.WORST)) {
        worstBoxes.firstNotNullOfOrNull { it.intersection(obstacle) }?.let {
            issues += CollisionIssue(item.id, CollisionKind.OBSTACLE, null, PlacementStatus.UNCERTAIN, location(it, config))
        }
    }
    val conflicts = space.obstacles(Bound.BEST).mapNotNull { obstacle -> bestBoxes.firstNotNullOfOrNull { it.intersection(obstacle) } }
    if (conflicts.isEmpty()) return issues
    // A proved conflict replaces the doubtful entries for the same obstacles.
    return conflicts.map { CollisionIssue(item.id, CollisionKind.OBSTACLE, null, PlacementStatus.CONFLICT, location(it, config)) }
}

private fun instanceIssue(a: PlacedGeometry, b: PlacedGeometry, config: GeometryConfig): CollisionIssue? {
    val best = pairOverlap(a.boxes(Bound.BEST), b.boxes(Bound.BEST))
    if (best != null) return CollisionIssue(a.id, CollisionKind.INSTANCE, b.id, PlacementStatus.CONFLICT, location(best, config))
    val worst = pairOverlap(a.boxes(Bound.WORST, config.marginMm), b.boxes(Bound.WORST, config.marginMm)) ?: return null
    return CollisionIssue(a.id, CollisionKind.INSTANCE, b.id, PlacementStatus.UNCERTAIN, location(worst, config))
}

private fun pairOverlap(a: List<Box>, b: List<Box>): Box? =
    a.firstNotNullOfOrNull { left -> b.firstNotNullOfOrNull { left.intersection(it) } }

private fun boundsClearance(item: PlacedGeometry, space: ProfileSpace): Long {
    val b = space.bounds
    return item.boxes(Bound.NOMINAL).minOf { box ->
        val walls = minOf(box.minX, b.x - box.maxX, minOf(box.minZ, b.z - box.maxZ, b.y - box.maxY))
        // Resting on the floor is intended contact; sinking below it is still a negative clearance.
        if (box.minY < 0) minOf(walls, box.minY) else walls
    }
}

private fun instanceClearance(a: PlacedGeometry, b: PlacedGeometry): Long =
    a.boxes(Bound.NOMINAL).minOf { left -> b.boxes(Bound.NOMINAL).minOf { left.separation(it) } }

/** Snapshot coordinates must stay inside the wire range even when worst bounds reach beyond it. */
private fun location(box: Box, config: GeometryConfig): Vec3Mm {
    val limit = config.coordinateAbsMaxMm
    return Vec3Mm(box.minX.coerceIn(-limit, limit), box.minY.coerceIn(-limit, limit), box.minZ.coerceIn(-limit, limit))
}
