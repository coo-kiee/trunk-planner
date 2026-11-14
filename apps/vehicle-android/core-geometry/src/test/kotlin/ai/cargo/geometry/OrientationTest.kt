package ai.cargo.geometry

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.RotationRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrientationTest {
    private val root = File(System.getProperty("cargo.productRoot"))

    @Test fun twenty_four_unique_proper_rotations_match_contract_file() {
        val fromFile = Json.parseToJsonElement(root.resolve("packages/contracts/rotations-v1.json").readText())
            .jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }
        val ours = (0 until Orientations.COUNT).map(Orientations::matrix)
        assertEquals(fromFile, ours)
        assertEquals(24, ours.toSet().size)
        assertEquals(listOf(1, 0, 0, 0, 1, 0, 0, 0, 1), ours[0], "identity stays index 0")
        for (m in ours) {
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            assertEquals(1, det, "right-handed proper rotation")
        }
    }

    @Test fun normalization_keeps_a_zero_min_corner_and_extents() {
        val geometry = ItemGeometry.of(Fx.lItem(), GeometryConfig.V1)
        val distinctShapes = (0 until 24).map { geometry.oriented(it) }
        for (shape in distinctShapes) {
            assertEquals(0, shape.boxes.minOf { it.minX })
            assertEquals(0, shape.boxes.minOf { it.minY })
            assertEquals(0, shape.boxes.minOf { it.minZ })
            assertEquals(listOf(100L, 300L, 300L), listOf(shape.extent.x, shape.extent.y, shape.extent.z).sorted())
        }
        assertEquals(24, distinctShapes.map { it.boxes.toSet() }.toSet().size, "an L has 24 distinct poses")
    }

    @Test fun CE07_keep_upright_allows_exactly_four_yaws() {
        val allowed = Orientations.allowed(RotationRule.KEEP_UPRIGHT, Vec(100, 200, 300))
        assertEquals(4, allowed.size)
        allowed.forEach { assertEquals(200, kotlin.math.abs(Orientations.rotate(it, Vec(100, 200, 300)).y)) }
    }

    @Test fun flat_only_keeps_the_largest_face_down() {
        val allowed = Orientations.allowed(RotationRule.FLAT_ONLY, Vec(100, 200, 300))
        assertEquals(8, allowed.size)
        allowed.forEach { assertEquals(100, kotlin.math.abs(Orientations.rotate(it, Vec(100, 200, 300)).y)) }
        assertEquals(24, Orientations.allowed(RotationRule.ANY24, Vec(1, 2, 3)).size)
    }

    @Test fun orientation_outside_rule_is_a_handling_violation_independent_of_placement() {
        val laidDown = (0L until 24).first { it.toInt() !in Orientations.allowed(RotationRule.KEEP_UPRIGHT, Vec(100, 200, 300)) }
        val g = Fx.judged(listOf(Fx.placed(1, Fx.item(1, 100, 200, 300, rule = RotationRule.KEEP_UPRIGHT), 0, 0, 0, laidDown)), Fx.profile())
        assertEquals(PlacementStatus.CLEAR, g.placement.status)
        assertEquals(HandlingIssueKind.ROTATION_NOT_ALLOWED, g.handlingIssues.single().kind)
    }

    @Test fun contract_fixtures_decode_through_the_shared_codec() {
        val fixtures = Json.parseToJsonElement(root.resolve("packages/contracts/fixtures.json").readText()).jsonArray
        val valid = fixtures.first { it.toString().contains("orientation-0") }
        val value = (valid as kotlinx.serialization.json.JsonObject)["value"].toString()
        val envelope = ContractCodec.decode(value)
        assertTrue(envelope.scene.instances.isNotEmpty())
        val g = Fx.judged(emptyList(), envelope.profile)
        assertEquals(PlacementStatus.CLEAR, g.placement.status)
    }
}
