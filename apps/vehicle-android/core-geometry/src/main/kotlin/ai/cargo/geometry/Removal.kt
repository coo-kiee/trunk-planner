package ai.cargo.geometry

enum class RemovalVerdict { CLEAR_IN_CHECKED_CLASS, BLOCKED_IN_CHECKED_CLASS, ERROR_DECIDES }

/**
 * Warning, not a verdict on whether removal is possible. Smaller removalPriority comes out earlier.
 * Checks the reverse straight z sweep from the pose against items that come out later.
 */
data class RemovalDetail(val instanceId: String, val verdict: RemovalVerdict, val blockedBy: List<String>)

internal fun evaluateRemoval(subject: PlacedGeometry, others: List<PlacedGeometry>, config: GeometryConfig): RemovalDetail {
    val later = others.filter { it.item.item.removalPriority > subject.item.item.removalPriority }
    val best = later.filter { blocksReverseZ(subject, it, Bound.BEST, 0) }
    if (best.isNotEmpty()) return RemovalDetail(subject.id, RemovalVerdict.BLOCKED_IN_CHECKED_CLASS, best.map { it.id })
    val worst = later.filter { blocksReverseZ(subject, it, Bound.WORST, config.marginMm) }
    return if (worst.isNotEmpty()) {
        RemovalDetail(subject.id, RemovalVerdict.ERROR_DECIDES, worst.map { it.id })
    } else {
        RemovalDetail(subject.id, RemovalVerdict.CLEAR_IN_CHECKED_CLASS, emptyList())
    }
}

private fun blocksReverseZ(subject: PlacedGeometry, other: PlacedGeometry, bound: Bound, margin: Long): Boolean {
    val otherBoxes = other.boxes(bound, margin)
    val pose = subject.pose
    val outside = Vec(pose.x, pose.y, -(subject.shape.extent.z + subject.item.errorMm + margin + 1))
    val from = subject.shape.boxesAt(pose, bound, subject.item.errorMm, margin)
    val to = subject.shape.boxesAt(outside, bound, subject.item.errorMm, margin)
    return from.indices.any { index -> from[index].hull(to[index]).let { swept -> otherBoxes.any(swept::overlaps) } }
}
