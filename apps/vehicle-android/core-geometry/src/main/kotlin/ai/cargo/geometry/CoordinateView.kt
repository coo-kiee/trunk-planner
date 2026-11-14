package ai.cargo.geometry

/**
 * Vehicle frame: origin at the opening floor on the vehicle's right, +x vehicle left, +y up, +z toward the front.
 * "Vehicle left" and "screen left" coincide only when looking inward from the opening.
 */
enum class ViewFrame {
    /** From the opening toward +z: +x runs to the screen left, x=0 sits at the screen right. */
    OPENING_LOOKING_INWARD,

    /** From the driver's seat toward the rear (-z): +x runs to the screen right. */
    CABIN_LOOKING_REAR,
}

enum class VehicleSide { LEFT, RIGHT, CENTER }

/** Screen millimetres: the origin is the left edge of the cargo width, screenX grows to the right, screenY grows up. */
data class ScreenPointMm(val screenX: Long, val screenY: Long)

object CoordinateView {
    fun project(x: Long, y: Long, widthMm: Long, frame: ViewFrame): ScreenPointMm = when (frame) {
        ViewFrame.OPENING_LOOKING_INWARD -> ScreenPointMm(widthMm - x, y)
        ViewFrame.CABIN_LOOKING_REAR -> ScreenPointMm(x, y)
    }

    /** Side of the vehicle a move along x goes to, independent of how the view is oriented. */
    fun sideOfMove(deltaX: Long): VehicleSide = when {
        deltaX > 0 -> VehicleSide.LEFT
        deltaX < 0 -> VehicleSide.RIGHT
        else -> VehicleSide.CENTER
    }
}
