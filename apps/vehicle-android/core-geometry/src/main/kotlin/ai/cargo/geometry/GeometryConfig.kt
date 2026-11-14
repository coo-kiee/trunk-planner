package ai.cargo.geometry

/**
 * Values of packages/contracts/configuration-v1.json. The contract file stays the authority;
 * ConfigurationContractTest fails the build whenever these copies drift from it.
 */
data class GeometryConfig(
    val revision: Long,
    val engineVersion: String,
    val inputDimensionMaxMm: Long,
    val coordinateAbsMaxMm: Long,
    val maxInstances: Int,
    val maxProxyBoxesPerItem: Int,
    val marginMm: Long,
    /** Provisional projected-support policy, not a load securing or safety standard. */
    val supportRatioBasisPoints: Long,
) {
    companion object {
        val V1 = GeometryConfig(
            revision = 1,
            engineVersion = "aabb-mm-v1",
            inputDimensionMaxMm = 10_000,
            coordinateAbsMaxMm = 10_000,
            maxInstances = 100,
            maxProxyBoxesPerItem = 16,
            marginMm = 0,
            supportRatioBasisPoints = 10_000,
        )
        const val BASIS_POINTS_FULL = 10_000L
        const val SNAPSHOT_ERROR_MAX_MM = 1_000L
        const val SAFETY_NOT_ASSESSED = "NOT_ASSESSED"
        const val PATH_CLASS = "Z_THEN_XY_AABB_SWEEP_V1"
    }
}
