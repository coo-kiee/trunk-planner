package ai.cargo.sync

import ai.cargo.contracts.ItemDraft
import ai.cargo.data.AcceptResult
import ai.cargo.data.InboundDecision
import ai.cargo.data.InboundKey
import ai.cargo.data.InboundStatus
import ai.cargo.data.PairingStatus
import ai.cargo.data.ReceiveResult

sealed interface SyncStatus {
    data class Disabled(val problem: EndpointProblem) : SyncStatus
    data object NotPaired : SyncStatus

    /** The Keystore or file was lost. The draft contents and Room stay intact; a new pairing is required. */
    data object CredentialLost : SyncStatus
    data class Paired(val pairingId: String, val credentialExpiresAtMs: Long) : SyncStatus
    data class NetworkUnavailable(val pairingId: String?) : SyncStatus
    data class Throttled(val code: String, val retryAfterSeconds: Long?) : SyncStatus
    data class RevokePending(val pairingId: String) : SyncStatus
    data class Revoked(val pairingId: String) : SyncStatus
    data class Expired(val pairingId: String) : SyncStatus
    data class ProtocolError(val detail: String) : SyncStatus
}

sealed interface PairingStart {
    /** The QR is held in memory only. It is not restored after a restart: issue a new one instead. */
    data class WaitingClaim(val pairingId: String, val qrUrl: QrUrl, val claimExpiresAtMs: Long) : PairingStart
    data object PriorRevokePending : PairingStart
    data class Refused(val status: SyncStatus) : PairingStart
}

/** Conflict is what the vehicle reports to the phone; the user still decides on the vehicle. */
data class InboundView(val key: InboundKey, val draft: ItemDraft, val conflict: ConflictBody?, val pairingActive: Boolean, val quarantined: Boolean)

sealed interface DecisionResult {
    data class Accepted(val itemId: String, val revision: Long, val delivery: AckDelivery?) : DecisionResult
    data class Kept(val delivery: AckDelivery?) : DecisionResult
    data class ChoiceRequired(val baseRevision: Long?, val currentRevision: Long) : DecisionResult
    data class AlreadyDecided(val status: String, val itemId: String?, val itemRevision: Long?) : DecisionResult
    data class PairingInactive(val status: String) : DecisionResult
    data object Superseded : DecisionResult

    /** The relay's content for this key changed, so nothing the user chooses here could be reported honestly. */
    data object Quarantined : DecisionResult
    data object NotFound : DecisionResult
}

data class PollReport(val status: SyncStatus, val received: Int, val warnings: List<String>)

private fun keyOf(draft: ItemDraft) = InboundKey(draft.pairingId, draft.draftId, draft.clientRevision)

private val ACTIVE = setOf("PENDING", "CONFLICT_REPORTED")
private const val PAGE = 50
private const val MAX_PAGES = 20

/**
 * Vehicle side of the relay protocol. All calls are outbound and blocking; callers run them off the main thread.
 * The relay is never authoritative: Room decides, and this class reports the decision and how far it travelled.
 */
class SyncEngine(
    private val endpoints: RelayEndpoints,
    private val transport: RelayTransport,
    private val inbound: InboundGateway,
    private val journal: SyncJournal,
    private val credentials: CredentialStore,
    private val photos: PhotoStore,
    private val nowMs: () -> Long,
    private val isoNow: () -> String,
) {
    private val client = (endpoints as? RelayEndpoints.Enabled)?.let { RelayClient(transport, it) }

    @Synchronized fun startPairing(): PairingStart {
        val relay = client ?: return PairingStart.Refused(SyncStatus.Disabled((endpoints as RelayEndpoints.Disabled).problem))
        // The relay never revokes on its own, so a previous pairing is ended first. Failure blocks issuing a new QR.
        val current = revoke()
        if (current is SyncStatus.RevokePending) return PairingStart.PriorRevokePending
        return when (val created = relay.createPairing()) {
            is RelayResult.Ok -> {
                val value = created.value
                // Until a claim the relay's value is only the claim limit. The Room row waits for the real credential end.
                credentials.save(VehicleCredential(value.pairingId, value.bearer, value.credentialExpiresAtMs))
                PairingStart.WaitingClaim(value.pairingId, value.qrUrl, value.claimExpiresAtMs)
            }
            else -> PairingStart.Refused(statusOf(created, null))
        }
    }

    /** Local revocation is immediate and durable; the relay call retries while the bearer is kept. */
    @Synchronized fun revoke(): SyncStatus {
        val relay = client
        val credential = (credentials.load() as? CredentialLoad.Found)?.credential ?: return SyncStatus.NotPaired
        if (relay != null) flushAcks()
        journal.setPendingRevoke(credential.pairingId)
        inbound.revokePairing(credential.pairingId, nowMs())
        return settleRevoke(credential)
    }

    private fun settleRevoke(credential: VehicleCredential): SyncStatus {
        val relay = client ?: return SyncStatus.RevokePending(credential.pairingId)
        return when (val answer = relay.revoke(credential.bearer)) {
            is RelayResult.Ok -> finishRevoke(credential)
            // Each of these proves the credential can no longer be used.
            is RelayResult.Rejected -> when (answer.code) {
                "PAIRING_REVOKED", "CREDENTIAL_EXPIRED", "UNAUTHENTICATED" -> finishRevoke(credential)
                else -> SyncStatus.RevokePending(credential.pairingId)
            }
            else -> SyncStatus.RevokePending(credential.pairingId)
        }
    }

    private fun finishRevoke(credential: VehicleCredential): SyncStatus {
        credentials.clear()
        journal.setPendingRevoke(null)
        terminateUnsent(AckDelivery.UNDELIVERED_REVOKED, "revoked")
        return SyncStatus.Revoked(credential.pairingId)
    }

    /** One foreground cycle: resume anything durable, then fetch. The result is safe to show and repeat. */
    @Synchronized fun poll(): PollReport {
        val relay = client ?: return PollReport(SyncStatus.Disabled((endpoints as RelayEndpoints.Disabled).problem), 0, emptyList())
        val credential = when (val load = credentials.load()) {
            CredentialLoad.None -> {
                terminateUnsent(AckDelivery.UNDELIVERED_REVOKED, "no credential")
                return PollReport(SyncStatus.NotPaired, 0, emptyList())
            }
            CredentialLoad.Unrecoverable -> return PollReport(SyncStatus.CredentialLost, 0, emptyList())
            is CredentialLoad.Found -> load.credential
        }
        if (journal.pendingRevoke() == credential.pairingId) return PollReport(settleRevoke(credential), 0, emptyList())
        val current = when (val refreshed = refreshFromServer(relay, credential)) {
            is ServerView.Ended -> return PollReport(refreshed.status, 0, emptyList())
            is ServerView.Unknown -> return PollReport(refreshed.status, 0, emptyList())
            is ServerView.Usable -> refreshed.credential
        }
        if (!current.claimed) {
            flushAcks()
            return PollReport(SyncStatus.NotPaired, 0, emptyList())
        }
        // Also the recovery for a crash between saving the claim and creating this row.
        if (inbound.pairingStatus(current.pairingId, nowMs()) == null) {
            inbound.createPairing(current.pairingId, sha256Hex(current.bearer.reveal().toByteArray()), nowMs(), current.credentialExpiresAtMs)
        }
        if (inbound.pairingStatus(current.pairingId, nowMs()) == PairingStatus.EXPIRED) {
            // The server still accepts this credential but Room cannot be moved: a row from before this fix, or a clock ahead
            // of the server. Owed acks still go out, and a new QR is the way forward. Calling this expired would be false.
            flushAcks()
            return PollReport(SyncStatus.ProtocolError("LOCAL_PAIRING_EXPIRED_SERVER_ACTIVE"), 0, emptyList())
        }
        flushAcks()
        val warnings = ArrayList<String>()
        var received = 0
        var after = 0L
        repeat(MAX_PAGES) {
            when (val page = relay.listDrafts(current.bearer, after, PAGE)) {
                is RelayResult.Ok -> {
                    page.value.drafts.forEach { row -> if (admit(current, row, warnings)) received++ }
                    if (page.value.drafts.size < PAGE) {
                        flushAcks()
                        return PollReport(SyncStatus.Paired(current.pairingId, current.credentialExpiresAtMs), received, warnings)
                    }
                    after = page.value.drafts.last().seq
                }
                is RelayResult.Rejected -> return PollReport(serverEnded(page, current) ?: statusOf(page, current.pairingId), received, warnings)
                else -> return PollReport(statusOf(page, current.pairingId), received, warnings)
            }
        }
        return PollReport(SyncStatus.Paired(current.pairingId, current.credentialExpiresAtMs), received, warnings)
    }

    private sealed interface ServerView {
        data class Usable(val credential: VehicleCredential) : ServerView
        data class Ended(val status: SyncStatus) : ServerView

        /** No answer that settles it. Nothing is written off: owed acks stay owed until the server has spoken. */
        data class Unknown(val status: SyncStatus) : ServerView
    }

    /**
     * The server owns the claim and the credential end. It is asked while unclaimed, and again once the stored end has
     * passed, because a local clock alone proves neither. A claimed credential inside its end needs no question.
     */
    private fun refreshFromServer(relay: RelayClient, credential: VehicleCredential): ServerView {
        if (credential.claimed && nowMs() < credential.credentialExpiresAtMs) return ServerView.Usable(credential)
        return when (val answer = relay.status(credential.bearer)) {
            is RelayResult.Ok -> {
                if (answer.value.pairingId != credential.pairingId) return ServerView.Unknown(SyncStatus.ProtocolError("PAIRING_STATUS_MISMATCH"))
                val updated = credential.copy(claimed = answer.value.claimed, credentialExpiresAtMs = answer.value.credentialExpiresAtMs)
                if (updated != credential) credentials.save(updated)
                ServerView.Usable(updated)
            }
            is RelayResult.Rejected -> serverEnded(answer, credential)?.let { ServerView.Ended(it) } ?: ServerView.Unknown(statusOf(answer, credential.pairingId))
            else -> ServerView.Unknown(statusOf(answer, credential.pairingId))
        }
    }

    /** Only a server refusal ends a link and its owed acks. Null means this refusal does not settle the credential. */
    private fun serverEnded(answer: RelayResult.Rejected, credential: VehicleCredential): SyncStatus? = when (endOf(answer.code, credential)) {
        AckDelivery.UNDELIVERED_EXPIRED -> {
            terminateUnsent(AckDelivery.UNDELIVERED_EXPIRED, answer.code)
            SyncStatus.Expired(credential.pairingId)
        }
        AckDelivery.UNDELIVERED_REVOKED -> {
            terminateUnsent(AckDelivery.UNDELIVERED_REVOKED, answer.code)
            SyncStatus.Revoked(credential.pairingId)
        }
        else -> null
    }

    /**
     * One reading of the credential refusals for status, listing and acks. An expired credential is eventually cleaned up
     * and then answers as UNAUTHENTICATED, so that code means expired past the stored end and revoked before it.
     */
    private fun endOf(code: String, credential: VehicleCredential?): AckDelivery? = when (code) {
        "PAIRING_REVOKED" -> AckDelivery.UNDELIVERED_REVOKED
        "CREDENTIAL_EXPIRED", "PAIRING_EXPIRED" -> AckDelivery.UNDELIVERED_EXPIRED
        "UNAUTHENTICATED" -> if (credential != null && nowMs() >= credential.credentialExpiresAtMs) AckDelivery.UNDELIVERED_EXPIRED else AckDelivery.UNDELIVERED_REVOKED
        else -> null
    }

    /**
     * The first hash seen binds the key: it is never overwritten. Journal first, then Room, so a crash in between
     * re-lists the same draft and Room treats it as new or duplicate. Any sign of other content quarantines the key.
     */
    private fun admit(credential: VehicleCredential, row: RelayDraft, warnings: MutableList<String>): Boolean {
        val known = journal.entry(row.key)
        if (known?.payloadQuarantined == true || contentDiffers(known, row)) return quarantine(row, warnings)
        remember(row)
        val draft = row.draft
        if (draft == null) {
            journal.update(row.key) { it.copy(invalidDraft = true) }
            return false
        }
        markOlderRevisionsSuperseded(row.key)
        val received = when (val result = inbound.receive(draft, nowMs())) {
            is ReceiveResult.Stored -> true
            is ReceiveResult.Duplicate -> false
            is ReceiveResult.PayloadMismatch -> return quarantine(row, warnings)
            is ReceiveResult.PairingInactive -> { warnings += "PAIRING_INACTIVE:${result.status}"; false }
            is ReceiveResult.Invalid -> { journal.update(row.key) { it.copy(invalidDraft = true) }; false }
        }
        val photoRef = draft.payload.photoRef
        if (row.photo != null && photoRef != null) fetchPhoto(credential, row.key, photoRef, warnings)
        return received
    }

    private fun contentDiffers(known: JournalEntry?, row: RelayDraft): Boolean = when {
        known != null && known.relayPayloadHash != row.payloadHash -> true
        // Valid before and invalid now, or the reverse: same hash or not, this is not the same content.
        known != null && known.invalidDraft != (row.draft == null) -> true
        // Journal lost, and Room already holds a draft under this key that this payload cannot be compared with.
        known == null && row.draft == null -> storedInRoom(row.key)
        else -> false
    }

    private fun storedInRoom(key: InboundKey) =
        inbound.pending().any { keyOf(it) == key } || inbound.unsentAcks().any { it.key == key }

    /** Keeps the bound hash if there is one. No ack: answering would endorse one of the two contents. */
    private fun quarantine(row: RelayDraft, warnings: MutableList<String>): Boolean {
        warnings += "PAYLOAD_MISMATCH:${row.key.draftId}"
        val base = journal.entry(row.key) ?: row.key.toEntry(row.payloadHash)
        if (!base.payloadQuarantined) journal.put(base.copy(payloadQuarantined = true))
        return false
    }

    private fun remember(row: RelayDraft) {
        val base = journal.entry(row.key) ?: row.key.toEntry(row.payloadHash)
        val merged = base.copy(
            photoRef = row.draft?.payload?.photoRef ?: base.photoRef,
            conflictReported = base.conflictReported || row.state == "CONFLICT_REPORTED",
            conflictAckSent = base.conflictAckSent || row.state == "CONFLICT_REPORTED",
            authoritativeItemId = row.conflict?.authoritativeItemId ?: base.authoritativeItemId,
            authoritativeRevision = row.conflict?.authoritativeRevision ?: base.authoritativeRevision,
        )
        if (merged != journal.entry(row.key)) journal.put(merged)
    }

    private fun markOlderRevisionsSuperseded(key: InboundKey) {
        journal.entries().filter { it.pairingId == key.pairingId && it.draftId == key.draftId && it.clientRevision < key.clientRevision && !it.superseded }
            .forEach { old -> journal.put(old.copy(superseded = true)) }
    }

    private fun fetchPhoto(credential: VehicleCredential, key: InboundKey, photoRef: String, warnings: MutableList<String>) {
        if (photos.fileFor(photoRef) != null) return
        when (val photo = client!!.photo(credential.bearer, key)) {
            is RelayResult.Ok -> if (!photos.storeVerified(photoRef, photo.value.declaredSha256, photo.value.bytes)) warnings += "PHOTO_HASH_MISMATCH:${key.draftId}"
            else -> warnings += "PHOTO_UNAVAILABLE:${key.draftId}"
        }
    }

    /**
     * Drafts waiting for the user. An older clientRevision is hidden once a newer one has arrived. A quarantined draft
     * stays visible so that it cannot silently vanish, but it cannot be decided.
     */
    @Synchronized fun inbox(): List<InboundView> = inbound.pending().mapNotNull { draft ->
        val key = keyOf(draft)
        val entry = journal.entry(key)
        if (entry?.superseded == true) return@mapNotNull null
        val conflict = if (entry?.authoritativeItemId != null && entry.authoritativeRevision != null) ConflictBody(entry.authoritativeItemId, entry.authoritativeRevision) else null
        InboundView(key, draft, conflict, inbound.pairingStatus(key.pairingId, nowMs()) == PairingStatus.ACTIVE, entry?.payloadQuarantined == true)
    }

    /** Survives restarts and outlasts the relay listing, unlike the warnings of a single poll. */
    fun quarantined(): List<InboundKey> = journal.entries().filter { it.payloadQuarantined }.map { it.key() }

    fun deliveryOf(key: InboundKey): AckDelivery? = journal.entry(key)?.terminal
    fun photoFile(photoRef: String?) = photos.fileFor(photoRef)

    /** The only entry that changes Room content, and only with a decision the UI got from the user. */
    @Synchronized fun decide(key: InboundKey, decision: InboundDecision): DecisionResult {
        if (journal.entry(key)?.payloadQuarantined == true) return DecisionResult.Quarantined
        if (journal.entry(key)?.superseded == true) return DecisionResult.Superseded
        val draft = inbound.pending().firstOrNull { keyOf(it) == key }
        return when (val result = inbound.decide(key, decision, nowMs(), isoNow())) {
            is AcceptResult.Accepted -> {
                flushAcks()
                DecisionResult.Accepted(result.itemId, result.itemRevision, deliveryOf(key))
            }
            AcceptResult.Kept -> {
                flushAcks()
                DecisionResult.Kept(deliveryOf(key))
            }
            is AcceptResult.ChoiceRequired -> {
                val itemId = draft?.baseItemId ?: draft?.payload?.id
                journal.update(key) { it.copy(conflictReported = true, authoritativeItemId = itemId, authoritativeRevision = result.currentRevision) }
                flushAcks()
                DecisionResult.ChoiceRequired(result.baseRevision, result.currentRevision)
            }
            is AcceptResult.AlreadyDecided -> DecisionResult.AlreadyDecided(result.status, result.itemId, result.itemRevision)
            is AcceptResult.PairingInactive -> DecisionResult.PairingInactive(result.status)
            AcceptResult.NotFound -> DecisionResult.NotFound
        }
    }

    /** Sends every durable obligation. Stops early on a transport problem and leaves the rest for the next cycle. */
    @Synchronized fun flushAcks() {
        val relay = client ?: return
        val credential = (credentials.load() as? CredentialLoad.Found)?.credential
        if (credential != null) {
            journal.entries().filter { it.pairingId == credential.pairingId && it.terminal == null && !it.payloadQuarantined }.forEach { entry ->
                if (entry.invalidDraft) {
                    if (!send(relay, credential, entry.key(), AckBody.Rejected("PENDING", entry.relayPayloadHash, RejectCode.VALIDATION_FAILED))) return
                } else if (entry.conflictReported && !entry.conflictAckSent) {
                    if (!sendConflict(relay, credential, entry)) return
                }
            }
        }
        inbound.unsentAcks().forEach { row ->
            val entry = journal.entry(row.key)
            when {
                entry?.terminal != null -> inbound.markAckSent(row.key, nowMs())
                // A decision committed before the mismatch stays in Room; it is simply never reported.
                entry?.payloadQuarantined == true -> finish(row.key, AckDelivery.REJECTED, "PAYLOAD_MISMATCH")
                credential == null || row.key.pairingId != credential.pairingId -> finish(row.key, AckDelivery.UNDELIVERED_REVOKED, "other or no pairing")
                entry == null -> if (!recoverByListing(relay, credential, row)) return
                else -> if (!sendTerminal(relay, credential, row, entry)) return
            }
        }
    }

    /** A lost journal entry is rebuilt from the relay list. Absence there means the draft outlived its TTL. */
    private fun recoverByListing(relay: RelayClient, credential: VehicleCredential, row: UnsentAck): Boolean {
        var after = 0L
        repeat(MAX_PAGES) {
            when (val page = relay.listDrafts(credential.bearer, after, PAGE)) {
                is RelayResult.Ok -> {
                    page.value.drafts.firstOrNull { it.key == row.key }?.let { found ->
                        // With no journal the hash is unbound, so Room's stored payload is what proves it is the same draft.
                        val same = found.draft?.let { inbound.receive(it, nowMs()) } is ReceiveResult.Duplicate
                        if (!same) {
                            quarantine(found, ArrayList())
                            finish(row.key, AckDelivery.REJECTED, "PAYLOAD_MISMATCH")
                            return true
                        }
                        remember(found)
                        return sendTerminal(relay, credential, row, journal.entry(row.key)!!)
                    }
                    if (page.value.drafts.size < PAGE) {
                        finish(row.key, AckDelivery.UNDELIVERED_EXPIRED, "not listed")
                        return true
                    }
                    after = page.value.drafts.last().seq
                }
                is RelayResult.Rejected -> return applyRejection(row.key, page)
                else -> return false
            }
        }
        return false
    }

    private fun sendTerminal(relay: RelayClient, credential: VehicleCredential, row: UnsentAck, entry: JournalEntry): Boolean {
        val expected = if (entry.conflictAckSent) "CONFLICT_REPORTED" else "PENDING"
        val ack: AckBody = when (row.status) {
            InboundStatus.ACCEPTED -> AckBody.Accepted(expected, entry.relayPayloadHash, row.itemId!!, row.itemRevision!!)
            InboundStatus.KEPT -> AckBody.Rejected(expected, entry.relayPayloadHash, if (entry.conflictReported) RejectCode.USER_KEPT_VEHICLE_VERSION else RejectCode.USER_DISCARDED)
            else -> {
                finish(row.key, AckDelivery.REJECTED, "unknown status ${row.status}")
                return true
            }
        }
        return send(relay, credential, row.key, ack)
    }

    /** True when this key is settled. False means a transport problem that the next cycle will retry. */
    private fun send(relay: RelayClient, credential: VehicleCredential, key: InboundKey, ack: AckBody, retried: Boolean = false): Boolean =
        when (val answer = relay.ack(credential.bearer, key, ack)) {
            is RelayResult.Ok -> {
                finish(key, AckDelivery.DELIVERED, null)
                true
            }
            is RelayResult.Rejected -> if (answer.code == "STATE_CONFLICT" && answer.currentState in ACTIVE && answer.currentState != ack.expectedState && !retried) {
                // Bounded: a lost response to an earlier conflict ack. The retry names the state the relay reported.
                send(relay, credential, key, ack.withExpected(answer.currentState!!), retried = true)
            } else {
                applyRejection(key, answer)
            }
            else -> false
        }

    private fun sendConflict(relay: RelayClient, credential: VehicleCredential, entry: JournalEntry): Boolean {
        val itemId = entry.authoritativeItemId
        val revision = entry.authoritativeRevision
        // A base item that no longer exists has no authoritative revision to name. The choice stays on the vehicle.
        if (itemId == null || revision == null || revision < 1) return true
        return when (val answer = relay.ack(credential.bearer, entry.key(), AckBody.Conflict("PENDING", entry.relayPayloadHash, itemId, revision))) {
            is RelayResult.Ok -> {
                journal.update(entry.key()) { it.copy(conflictAckSent = true) }
                true
            }
            is RelayResult.Rejected -> if (answer.code == "STATE_CONFLICT" && answer.currentState == "CONFLICT_REPORTED") {
                journal.update(entry.key()) { it.copy(conflictAckSent = true) }
                true
            } else {
                applyRejection(entry.key(), answer)
            }
            else -> false
        }
    }

    /** Room is never rolled back here: a committed decision stays, and only further delivery attempts end. */
    private fun applyRejection(key: InboundKey, answer: RelayResult.Rejected): Boolean {
        if (answer.status == 429 || answer.status >= 500) return false
        val delivery = when (answer.code) {
            "PAIRING_REVOKED", "CREDENTIAL_EXPIRED", "PAIRING_EXPIRED", "UNAUTHENTICATED" -> endOf(answer.code, (credentials.load() as? CredentialLoad.Found)?.credential)!!
            "DRAFT_EXPIRED" -> AckDelivery.UNDELIVERED_EXPIRED
            "STATE_CONFLICT" -> when (answer.currentState) {
                "EXPIRED" -> AckDelivery.UNDELIVERED_EXPIRED
                "REVOKED" -> AckDelivery.UNDELIVERED_REVOKED
                else -> AckDelivery.REJECTED
            }
            else -> AckDelivery.REJECTED
        }
        finish(key, delivery, listOfNotNull(answer.code, answer.currentState).joinToString(":"))
        return true
    }

    private fun finish(key: InboundKey, delivery: AckDelivery, detail: String?) {
        val existing = journal.entry(key) ?: key.toEntry("")
        journal.put(existing.copy(terminal = delivery, terminalDetail = detail))
        inbound.markAckSent(key, nowMs())
    }

    private fun terminateUnsent(delivery: AckDelivery, detail: String) {
        inbound.unsentAcks().forEach { row -> if (journal.entry(row.key)?.terminal == null) finish(row.key, delivery, detail) else inbound.markAckSent(row.key, nowMs()) }
    }

    private fun statusOf(result: RelayResult<*>, pairingId: String?): SyncStatus = when (result) {
        is RelayResult.Ok -> SyncStatus.NotPaired
        RelayResult.NetworkUnavailable -> SyncStatus.NetworkUnavailable(pairingId)
        is RelayResult.RedirectRefused -> SyncStatus.ProtocolError("REDIRECT_REFUSED:${result.status}")
        is RelayResult.Malformed -> SyncStatus.ProtocolError(result.detail)
        is RelayResult.Rejected -> when {
            result.status == 429 || result.status >= 500 -> SyncStatus.Throttled(result.code, result.retryAfterSeconds)
            else -> SyncStatus.ProtocolError(result.code)
        }
    }

    private fun AckBody.withExpected(state: String): AckBody = when (this) {
        is AckBody.Accepted -> copy(expectedState = state)
        is AckBody.Conflict -> copy(expectedState = state)
        is AckBody.Rejected -> copy(expectedState = state)
    }
}

/** Foreground only and bounded. Retry-After is a lower limit. There is deliberately no WorkManager or background job. */
object ForegroundRetryPolicy {
    const val IDLE_MS = 5_000L
    const val BASE_MS = 2_000L
    const val MAX_MS = 60_000L
    fun delayMs(failures: Int, retryAfterSeconds: Long?): Long {
        val exponential = if (failures <= 0) IDLE_MS else minOf(MAX_MS, BASE_MS shl minOf(failures - 1, 10))
        return maxOf(exponential, (retryAfterSeconds ?: 0) * 1000)
    }
}
