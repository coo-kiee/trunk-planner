package ai.cargo.vehicleport

import ai.cargo.vehicleport.core.SdkFailure
import ai.cargo.vehicleport.core.SdkPhase
import ai.cargo.vehicleport.core.classifySdkFailure
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SdkFailureTest {
    private val conn = Exception("Service connection failed")

    @Test fun security_type_wins_over_every_lifecycle_and_message() {
        SdkPhase.entries.forEach {
            assertEquals(SdkFailure.PermissionMissing, classifySdkFailure(SecurityException("Service connection failed"), it))
        }
    }

    @Test fun observed_wrapped_row3_message_is_area_rejected() =
        assertEquals(SdkFailure.AreaRejected, classifySdkFailure(Exception("java.lang.IllegalArgumentException: area ID: 0x100 not supported for property ID: DOOR_POS"), SdkPhase.READY))

    @Test fun same_connection_message_differs_by_lifecycle() {
        assertEquals(SdkFailure.NotConnected, classifySdkFailure(conn, SdkPhase.BEFORE_INITIALIZE))
        assertEquals(SdkFailure.NotConnected, classifySdkFailure(conn, SdkPhase.CONNECTING))
        assertEquals(SdkFailure.CallAfterRelease, classifySdkFailure(conn, SdkPhase.AFTER_RELEASE))
    }

    @Test fun plain_exception_is_not_guessed_into_a_known_state() {
        assertIs<SdkFailure.Unclassified>(classifySdkFailure(Exception("boom"), SdkPhase.READY))
        assertIs<SdkFailure.Unclassified>(classifySdkFailure(Exception("feature not supported yet"), SdkPhase.READY))
        assertIs<SdkFailure.Unclassified>(classifySdkFailure(Exception(null as String?), SdkPhase.READY))
    }
}
