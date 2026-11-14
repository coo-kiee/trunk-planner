package ai.cargo.feature.profile

import ai.cargo.contracts.ContractCodec
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.scene.SceneJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRulesTest {
    private val t = "2026-11-04T00:00:00Z"
    private fun form(vararg extra: Pair<SpaceField, String>) = ProfileForm(
        mapOf(SpaceField.X to "1000", SpaceField.Y to "800", SpaceField.Z to "1200", SpaceField.ERROR to "10") + extra,
    )

    @Test fun oneMeasurementIsUserMeasuredAndKeepsOpeningUnmeasured() {
        val p = assertNotNull(ProfileRules.measured(form(), "id", 1, t))
        assertEquals(ProfileGrade.USER_MEASURED, p.grade)
        assertNull(p.opening)
    }

    @Test fun repeatedMeasurementWidensErrorToItsSpread() {
        val p = assertNotNull(ProfileRules.measured(form(SpaceField.REPEAT_X to "1030", SpaceField.REPEAT_Y to "800", SpaceField.REPEAT_Z to "1200"), "id", 1, t))
        assertEquals(ProfileGrade.VERIFIED_MEASURED, p.grade)
        assertEquals(30, p.errorMm)
    }

    @Test fun halfAnOpeningIsAnErrorRatherThanAGuess() {
        assertTrue(SpaceField.OPENING_Y in ProfileRules.check(form(SpaceField.OPENING_X to "900")).errors)
    }

    @Test fun demoAndUnsupportedSayWhatTheyAre() {
        assertEquals(ProfileLabels.DEMO_LABEL, ProfileRules.demoSynthetic("id", 1, t).provenance)
        assertNull(ProfileRules.unsupported("id", 1, t).bounds)
    }

    private fun assertContractAccepts(form: ProfileForm) {
        val version = assertNotNull(ProfileRules.measured(form, "00000000-0000-4000-8000-000000000001", 1, t))
        ContractCodec.validateDefinition("ProfileVersion", SceneJson.json.encodeToString(ProfileVersion.serializer(), version))
    }

    @Test fun spreadBoundaryMatchesTheStoredErrorLimit() {
        assertContractAccepts(form(SpaceField.REPEAT_X to "2000", SpaceField.REPEAT_Y to "800", SpaceField.REPEAT_Z to "1200"))
        val tooFar = form(SpaceField.REPEAT_X to "2001", SpaceField.REPEAT_Y to "800", SpaceField.REPEAT_Z to "1200")
        assertTrue(SpaceField.REPEAT_X in ProfileRules.check(tooFar).errors)
        assertNull(ProfileRules.measured(tooFar, "id", 1, t))
    }

    @Test fun obstacleOriginBoundaryMatchesTheContract() {
        val size = arrayOf(SpaceField.OBSTACLE_X to "100", SpaceField.OBSTACLE_Y to "100", SpaceField.OBSTACLE_Z to "100")
        assertContractAccepts(form(*size, SpaceField.OBSTACLE_OX to "10000", SpaceField.OBSTACLE_OZ to "0"))
        assertTrue(SpaceField.OBSTACLE_OX in ProfileRules.check(form(*size, SpaceField.OBSTACLE_OX to "10001")).errors)
        assertTrue(SpaceField.OBSTACLE_OZ in ProfileRules.check(form(SpaceField.OBSTACLE_OZ to "5")).errors)
    }
}
