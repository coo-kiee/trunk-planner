package ai.cargo.geometry

import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.RotationsV1

/** Stable 24 proper rotations of rotations-v1; orientationIndex addresses this list directly. */
object Orientations {
    const val COUNT = 24
    private val matrices: List<List<Int>> = RotationsV1.matrices

    fun matrix(index: Int): List<Int> = matrices[index]

    fun isValidIndex(index: Long): Boolean = index in 0 until COUNT

    /** Applies the row-major matrix to a local vector. */
    fun rotate(index: Int, v: Vec): Vec {
        val m = matrices[index]
        return Vec(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z,
        )
    }

    /**
     * Orientations a rule permits for an item whose measured extents are given.
     * KEEP_UPRIGHT keeps the local +y axis on world +y (four yaw turns).
     * FLAT_ONLY keeps the smallest extent vertical, i.e. the largest face on the floor.
     */
    fun allowed(rule: RotationRule, extent: Vec): List<Int> = when (rule) {
        RotationRule.ANY24 -> (0 until COUNT).toList()
        RotationRule.KEEP_UPRIGHT -> (0 until COUNT).filter { matrices[it][4] == 1 }
        RotationRule.FLAT_ONLY -> {
            val smallest = minOf(extent.x, extent.y, extent.z)
            (0 until COUNT).filter { kotlin.math.abs(rotate(it, extent).y) == smallest }
        }
    }
}
