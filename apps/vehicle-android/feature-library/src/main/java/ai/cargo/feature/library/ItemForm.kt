package ai.cargo.feature.library

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm

enum class NumberField(val label: String) {
    X("폭 X (mm)"), Y("높이 Y (mm)"), Z("깊이 Z (mm)"),
    CUT_X("L자: 윗부분 폭 (mm)"), CUT_Y("L자: 아랫부분 높이 (mm)"),
    ERROR("치수 오차 ±(mm)"), PRIORITY("꺼냄 순서 (작을수록 먼저)"),
}

/** Text as typed, so that a half-entered number is never silently rounded into a dimension. */
data class ItemForm(
    val editingItemId: String? = null,
    val baseRevision: Long = 0,
    val createdAt: String? = null,
    val photoRef: String? = null,
    val name: String = "",
    val shape: ShapeKind = ShapeKind.BOX,
    val numbers: Map<NumberField, String> = mapOf(NumberField.ERROR to "5", NumberField.PRIORITY to "1"),
    val source: MeasurementSource = MeasurementSource.USER_TAPE,
    val rotation: RotationRule = RotationRule.ANY24,
    val stackable: Boolean = true,
    val confirmed: Boolean = false,
    val focused: NumberField = NumberField.X,
) {
    val visibleFields: List<NumberField>
        get() = listOf(NumberField.X, NumberField.Y, NumberField.Z) +
            (if (shape == ShapeKind.L_SHAPE) listOf(NumberField.CUT_X, NumberField.CUT_Y) else emptyList()) +
            listOf(NumberField.ERROR, NumberField.PRIORITY)

    fun text(field: NumberField) = numbers[field].orEmpty()
}

data class FormErrors(val name: String? = null, val fields: Map<NumberField, String> = emptyMap(), val unconfirmed: Boolean = false) {
    val isEmpty get() = name == null && fields.isEmpty() && !unconfirmed
}

/** Pure form rules: validation and the conversion to an immutable item version. */
object ItemFormRules {
    private const val MAX_MM = 10_000L
    private const val MAX_ERROR_MM = 1_000L
    private const val MAX_PRIORITY = 100L
    private const val MAX_NAME = 120

    fun fromVersion(item: ItemVersion): ItemForm {
        val d = item.measuredDimensionsMm
        val cut = if (item.shapeKind == ShapeKind.L_SHAPE && item.shapeProxy.size == 2) {
            mapOf(NumberField.CUT_X to item.shapeProxy[1].size.x.toString(), NumberField.CUT_Y to item.shapeProxy[0].size.y.toString())
        } else {
            emptyMap()
        }
        return ItemForm(
            editingItemId = item.id,
            baseRevision = item.revision,
            createdAt = item.createdAt,
            photoRef = item.photoRef,
            name = item.name,
            shape = item.shapeKind,
            numbers = mapOf(
                NumberField.X to d.x.toString(), NumberField.Y to d.y.toString(), NumberField.Z to d.z.toString(),
                NumberField.ERROR to item.errorMm.toString(), NumberField.PRIORITY to item.removalPriority.toString(),
            ) + cut,
            source = item.measurementSource,
            rotation = item.rotationRule,
            stackable = item.stackable,
            // A new version needs its own confirmation, so an old confirmation never carries over.
            confirmed = false,
        )
    }

    fun errors(form: ItemForm): FormErrors {
        val fields = HashMap<NumberField, String>()
        form.visibleFields.forEach { field ->
            val value = form.text(field).toLongOrNull()
            val problem = when (field) {
                NumberField.ERROR -> if (value == null || value !in 0..MAX_ERROR_MM) "0~$MAX_ERROR_MM mm로 입력하세요" else null
                NumberField.PRIORITY -> if (value == null || value !in 0..MAX_PRIORITY) "0~${MAX_PRIORITY}으로 입력하세요" else null
                else -> if (value == null || value !in 1..MAX_MM) "1~$MAX_MM mm로 입력하세요" else null
            }
            if (problem != null) fields[field] = problem
        }
        if (form.shape == ShapeKind.L_SHAPE) lShapeProblems(form, fields)
        return FormErrors(nameProblem(form.name), fields, !form.confirmed)
    }

    private fun nameProblem(name: String): String? = when {
        name.isBlank() -> "이름을 입력하세요"
        name.trim().length > MAX_NAME -> "이름은 ${MAX_NAME}자까지 입력할 수 있습니다(지금 ${name.trim().length}자)"
        else -> null
    }

    private fun lShapeProblems(form: ItemForm, fields: MutableMap<NumberField, String>) {
        val x = form.text(NumberField.X).toLongOrNull()
        val y = form.text(NumberField.Y).toLongOrNull()
        val cutX = form.text(NumberField.CUT_X).toLongOrNull()
        val cutY = form.text(NumberField.CUT_Y).toLongOrNull()
        if (x != null && cutX != null && cutX >= x) fields[NumberField.CUT_X] = "전체 폭보다 작아야 합니다"
        if (y != null && cutY != null && cutY >= y) fields[NumberField.CUT_Y] = "전체 높이보다 작아야 합니다"
    }

    /** L shape is two boxes: a lower slab and a narrower upper part. It is a conservative proxy, not a mesh. */
    fun proxies(form: ItemForm): List<BoxProxy> {
        val x = form.text(NumberField.X).toLong()
        val y = form.text(NumberField.Y).toLong()
        val z = form.text(NumberField.Z).toLong()
        if (form.shape != ShapeKind.L_SHAPE) return listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, y, z)))
        val cutX = form.text(NumberField.CUT_X).toLong()
        val cutY = form.text(NumberField.CUT_Y).toLong()
        return listOf(
            BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(x, cutY, z)),
            BoxProxy(Vec3Mm(0, cutY, 0), DimensionsMm(cutX, y - cutY, z)),
        )
    }

    /** Null while anything is invalid or unconfirmed: unconfirmed dimensions never reach storage. */
    fun toVersion(form: ItemForm, newId: () -> String, at: String): ItemVersion? {
        if (!errors(form).isEmpty) return null
        return ItemVersion(
            schemaVersion = 1,
            id = form.editingItemId ?: newId(),
            revision = form.baseRevision + 1,
            createdAt = form.createdAt ?: at,
            updatedAt = at,
            name = form.name.trim(),
            photoRef = form.photoRef,
            shapeKind = form.shape,
            shapeProxy = proxies(form),
            measuredDimensionsMm = DimensionsMm(form.text(NumberField.X).toLong(), form.text(NumberField.Y).toLong(), form.text(NumberField.Z).toLong()),
            errorMm = form.text(NumberField.ERROR).toLong(),
            measurementSource = form.source,
            rotationRule = form.rotation,
            stackable = form.stackable,
            removalPriority = form.text(NumberField.PRIORITY).toLong(),
            confirmedDimensions = true,
        )
    }
}

object ItemLabels {
    fun shape(kind: ShapeKind) = when (kind) {
        ShapeKind.BOX -> "상자"
        ShapeKind.L_SHAPE -> "L자(상자 2개)"
        ShapeKind.CYLINDER_BOUNDING_BOX -> "원통(외접 상자)"
    }

    fun rotation(rule: RotationRule) = when (rule) {
        RotationRule.ANY24 -> "모든 90° 회전 허용"
        RotationRule.KEEP_UPRIGHT -> "세움 유지(수평 회전만)"
        RotationRule.FLAT_ONLY -> "눕힘만(가장 넓은 면이 바닥)"
    }

    fun source(source: MeasurementSource) = when (source) {
        MeasurementSource.USER_TAPE -> "직접 줄자 측정"
        MeasurementSource.REPEATED_TAPE -> "반복 측정"
        MeasurementSource.PUBLISHED_PARTIAL -> "공개 부분 치수"
        MeasurementSource.DEMO_SYNTHETIC -> "합성 시연값(실제 측정 아님)"
    }

    fun dimensions(item: ItemVersion) = item.measuredDimensionsMm.let { "${it.x}×${it.y}×${it.z} mm ±${item.errorMm}" }
}
