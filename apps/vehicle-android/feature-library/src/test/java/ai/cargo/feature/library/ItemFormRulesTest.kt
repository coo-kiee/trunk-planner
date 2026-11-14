package ai.cargo.feature.library

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.ShapeKind
import ai.cargo.geometry.GeometryConfig
import ai.cargo.geometry.ItemGeometry
import ai.cargo.scene.SceneJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemFormRulesTest {
    private val t = "2026-11-04T00:00:00Z"
    private fun form(shape: ShapeKind = ShapeKind.BOX, confirmed: Boolean = true) = ItemForm(
        name = "상자", shape = shape, confirmed = confirmed,
        numbers = mapOf(
            NumberField.X to "400", NumberField.Y to "300", NumberField.Z to "500", NumberField.ERROR to "5",
            NumberField.PRIORITY to "1", NumberField.CUT_X to "200", NumberField.CUT_Y to "100",
        ),
    )

    @Test fun unconfirmedDimensionsNeverBecomeAVersion() {
        assertNull(ItemFormRules.toVersion(form(confirmed = false), { "id" }, t))
        assertTrue(ItemFormRules.errors(form(confirmed = false)).unconfirmed)
    }

    @Test fun outOfRangeIsRefused() {
        val bad = form().let { it.copy(numbers = it.numbers + (NumberField.X to "20000")) }
        assertNull(ItemFormRules.toVersion(bad, { "id" }, t))
        assertTrue(NumberField.X in ItemFormRules.errors(bad).fields)
    }

    @Test fun everyShapeIsAcceptedByTheGeometryEngine() {
        ShapeKind.entries.forEach { shape ->
            val version = assertNotNull(ItemFormRules.toVersion(form(shape), { "00000000-0000-4000-8000-000000000001" }, t), shape.name)
            ItemGeometry.of(version, GeometryConfig.V1)
        }
    }

    @Test fun lShapeCutMustFitInside() {
        val bad = form(ShapeKind.L_SHAPE).let { it.copy(numbers = it.numbers + (NumberField.CUT_X to "400")) }
        assertTrue(NumberField.CUT_X in ItemFormRules.errors(bad).fields)
    }

    @Test fun newVersionNeedsItsOwnConfirmationAndIncrementsRevision() {
        val first = assertNotNull(ItemFormRules.toVersion(form(), { "00000000-0000-4000-8000-000000000001" }, t))
        val edit = ItemFormRules.fromVersion(first)
        assertFalse(edit.confirmed)
        val second = assertNotNull(ItemFormRules.toVersion(edit.copy(confirmed = true), { error("keeps its id") }, t))
        assertEquals(first.id, second.id)
        assertEquals(2, second.revision)
    }

    private fun withPriority(value: String) = form().let { it.copy(numbers = it.numbers + (NumberField.PRIORITY to value)) }

    /** Every value the form lets through must also pass the stored contract, or a save would be refused later. */
    private fun assertContractAccepts(form: ItemForm) {
        val version = assertNotNull(ItemFormRules.toVersion(form, { "00000000-0000-4000-8000-000000000001" }, t))
        ContractCodec.validateDefinition("ItemVersion", SceneJson.json.encodeToString(ItemVersion.serializer(), version))
    }

    @Test fun priorityBoundaryMatchesTheContract() {
        assertContractAccepts(withPriority("0"))
        assertContractAccepts(withPriority("100"))
        assertTrue(NumberField.PRIORITY in ItemFormRules.errors(withPriority("101")).fields)
        assertNull(ItemFormRules.toVersion(withPriority("101"), { "id" }, t))
    }

    @Test fun nameBoundaryMatchesTheContract() {
        assertContractAccepts(form().copy(name = "가".repeat(120)))
        assertNotNull(ItemFormRules.errors(form().copy(name = "가".repeat(121))).name)
        assertNotNull(ItemFormRules.errors(form().copy(name = "   ")).name)
    }

    @Test fun dimensionAndErrorBoundariesMatchTheContract() {
        fun with(field: NumberField, value: String) = form().let { it.copy(numbers = it.numbers + (field to value)) }
        assertContractAccepts(with(NumberField.X, "10000"))
        assertContractAccepts(with(NumberField.ERROR, "1000"))
        assertTrue(NumberField.X in ItemFormRules.errors(with(NumberField.X, "10001")).fields)
        assertTrue(NumberField.ERROR in ItemFormRules.errors(with(NumberField.ERROR, "1001")).fields)
    }
}
