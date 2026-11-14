package ai.cargo.feature.editor

import ai.cargo.contracts.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraFitRulesTest {
    private val t = "2026-01-01T00:00:00Z"
    private val profile = ProfileVersion(
        1, "00000000-0000-4000-8000-000000000002", 1, t, t, ProfileGrade.DEMO_SYNTHETIC, DimensionsMm(1500, 900, 1500),
        emptyList(), null, 0, "x", null, null, "m", emptyList(),
    )
    private val fit = EditorRules.fitCamera(profile)

    @Test fun onlyTheStoredDefaultIsFitted() {
        assertTrue(EditorRules.isFitCamera(fit, profile))
        assertFalse(EditorRules.isFitCamera(fit.copy(distanceMm = fit.distanceMm + 1), profile))
        assertFalse(EditorRules.isFitCamera(fit.copy(yawDegrees = 15), profile))
        assertFalse(EditorRules.isFitCamera(fit, null))
    }

    @Test fun buttonsStartFromTheDrawnDistanceOfTheDefaultView() {
        assertEquals(6000, EditorRules.cameraOnScreen(fit, profile, 6000).distanceMm)
        assertEquals(fit, EditorRules.cameraOnScreen(fit, profile, null))
    }

    @Test fun aChosenCameraStartsFromItself() {
        val chosen = fit.copy(yawDegrees = 15, distanceMm = 900)
        assertEquals(chosen, EditorRules.cameraOnScreen(chosen, profile, 6000))
    }

    @Test fun theDrawnDistanceNeverPullsTheDefaultCloser() {
        assertEquals(fit.distanceMm, EditorRules.cameraOnScreen(fit, profile, 100).distanceMm)
    }

    @Test fun storedDefaultStaysTheSameValueSavedDraftsAlreadyHold() {
        assertEquals(Camera(Vec3Mm(750, 300, 750), 0, 25, 3750), fit)
    }
}
