package ai.cargo.geometry

import ai.cargo.contracts.OpeningStatus

data class OpeningDetail(val instanceId: String, val status: OpeningStatus, val orientationIndex: Int?)

/**
 * Whether the cross-section can enter the opening in some permitted orientation. Dimension fit only:
 * it says nothing about a continuous route, which PathSweep reports independently. The opening rectangle starts at
 * the origin (opening floor, vehicle right) and spans opening.x and opening.y; opening.z is stored but does not limit fit.
 * Profile error widens or narrows the width on both sides and the height at the top only (ProfileSpace.openingWindow).
 */
internal fun judgeOpening(item: PlacedGeometry, space: ProfileSpace, config: GeometryConfig): OpeningDetail {
    val worst = space.openingWindow(Bound.WORST) ?: return OpeningDetail(item.id, OpeningStatus.OPENING_UNMEASURED, null)
    val best = space.openingWindow(Bound.BEST)!!
    val e = item.item.errorMm
    val margin = config.marginMm
    val shapes = item.item.allowedOrientations().map { item.item.oriented(it) }
    // Same window and same worst item extents as the route cross-section: FITS implies a worst entry exists.
    val worstFit = shapes.firstOrNull { worst.admits(it.extent.x + e + 2 * margin, it.extent.y + e + margin) }
    if (worstFit != null) return OpeningDetail(item.id, OpeningStatus.FITS, worstFit.orientationIndex)
    val bestFit = shapes.firstOrNull { best.admits(maxOf(1, it.extent.x - e), maxOf(1, it.extent.y - e)) }
    return if (bestFit != null) {
        OpeningDetail(item.id, OpeningStatus.UNCERTAIN, bestFit.orientationIndex)
    } else {
        OpeningDetail(item.id, OpeningStatus.DOES_NOT_FIT, null)
    }
}
