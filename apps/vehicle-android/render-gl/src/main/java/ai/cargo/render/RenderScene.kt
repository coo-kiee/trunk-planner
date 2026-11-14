package ai.cargo.render

/** Axis-aligned box in vehicle millimetres. Render data only; judgement never reads it back. */
data class RenderAabb(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
    val centerZ get() = (minZ + maxZ) / 2
}

/** What an instance means to the viewer. Each role has exactly one palette color. */
enum class RenderRole { ITEM, SELECTED, CONFLICT, UNVERIFIED }

data class RenderInstance(val id: String, val boxes: List<RenderAabb>, val role: RenderRole)

/** Vehicle frame: +x vehicle left, +y up, +z toward the cabin. Yaw 0 looks inward from the opening. */
data class RenderCamera(
    val targetX: Float, val targetY: Float, val targetZ: Float,
    val yawDegrees: Float, val pitchDegrees: Float, val distanceMm: Float,
    /** The default view, not one the user chose: drawn far enough back to show the whole scene on the actual surface. */
    val fitToSurface: Boolean = false,
)

data class RenderScene(
    val space: RenderAabb?,
    val obstacles: List<RenderAabb>,
    val instances: List<RenderInstance>,
) {
    companion object { val EMPTY = RenderScene(null, emptyList(), emptyList()) }
}
