package ai.cargo.feature.pairing

import ai.cargo.sync.AckDelivery
import ai.cargo.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PairingPresenterTest {
    @Test fun expiredDoesNotTurnIntoRevokedOnLaterPolls() {
        val expired = SyncStatus.Expired("p")
        assertEquals(expired, PairingPresenter.merge(expired, SyncStatus.Revoked("p")))
        assertEquals(expired, PairingPresenter.merge(expired, SyncStatus.NotPaired))
    }

    @Test fun aNewPairingReplacesAnEndedOne() {
        val paired = SyncStatus.Paired("q", 1)
        assertEquals(paired, PairingPresenter.merge(SyncStatus.Revoked("p"), paired))
    }

    @Test fun missingDeliveryIsNeverSuccess() {
        assertFalse(PairingPresenter.delivery(null).contains("수신함"))
        assertFalse(PairingPresenter.delivery(AckDelivery.UNDELIVERED_REVOKED).contains("수신함"))
    }
}
