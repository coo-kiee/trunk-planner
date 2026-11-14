package ai.cargo.geometry

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm

/** All vehicle dimensions here are synthetic test values and describe no real vehicle. */
object Fx {
    const val T = "2026-11-03T00:00:00Z"

    fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    fun item(
        n: Int = 1,
        x: Long = 100, y: Long = 100, z: Long = 100,
        e: Long = 0,
        rule: RotationRule = RotationRule.ANY24,
        stackable: Boolean = true,
        removal: Long = 0,
        confirmed: Boolean = true,
    ) = ItemVersion(
        schemaVersion = 1, id = uuid(n), revision = 1, createdAt = T, updatedAt = T, name = "item$n",
        shapeKind = ShapeKind.BOX,
        shapeProxy = listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, y, z))),
        measuredDimensionsMm = DimensionsMm(x, y, z),
        errorMm = e, measurementSource = MeasurementSource.DEMO_SYNTHETIC, rotationRule = rule,
        stackable = stackable, removalPriority = removal, confirmedDimensions = confirmed,
    )

    /** An L made of a 100x100x300 leg plus a 300x100x100 foot, bounding box 300x100x300. */
    fun lItem(n: Int = 9, e: Long = 0, rule: RotationRule = RotationRule.ANY24) = item(n, 300, 100, 300, e, rule).copy(
        shapeKind = ShapeKind.L_SHAPE,
        shapeProxy = listOf(
            BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(100, 100, 300)),
            BoxProxy(Vec3Mm(100, 0, 0), DimensionsMm(200, 100, 100)),
        ),
    )

    fun profile(
        x: Long = 1000, y: Long = 1000, z: Long = 1000,
        es: Long = 0,
        opening: DimensionsMm? = null,
        obstacles: List<BoxProxy> = emptyList(),
        grade: ProfileGrade = ProfileGrade.DEMO_SYNTHETIC,
    ) = ProfileVersion(
        schemaVersion = 1, id = uuid(100), revision = 1, createdAt = T, updatedAt = T, grade = grade,
        bounds = if (grade == ProfileGrade.UNSUPPORTED) null else DimensionsMm(x, y, z),
        obstacles = obstacles, opening = opening, errorMm = es,
        provenance = "synthetic test", method = "synthetic test", repeatMeasurements = emptyList(),
    )

    fun obstacle(x: Long, y: Long, z: Long, sx: Long, sy: Long, sz: Long) =
        BoxProxy(Vec3Mm(x, y, z), DimensionsMm(sx, sy, sz))

    fun placed(id: Int, item: ItemVersion, x: Long, y: Long, z: Long, orientation: Long = 0) =
        PlacedInput(uuid(id), item, Vec3Mm(x, y, z), orientation)

    fun judged(
        instances: List<PlacedInput>,
        profile: ProfileVersion,
        subjects: List<String> = emptyList(),
    ): SceneGeometry = (SceneJudge.judge(instances, profile, subjects) as SceneJudgement.Judged).geometry
}
