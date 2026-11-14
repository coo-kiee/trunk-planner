package ai.cargo.scene

import ai.cargo.contracts.Instance
import ai.cargo.contracts.JudgementSnapshot
import ai.cargo.contracts.PathResult
import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.SearchResult
import ai.cargo.contracts.SearchStatus
import ai.cargo.geometry.CancelSignal
import ai.cargo.geometry.CandidateSearch
import ai.cargo.geometry.GeometryConfig
import ai.cargo.geometry.HandlingIssueKind
import ai.cargo.geometry.InterruptCause
import ai.cargo.geometry.NotAvailableReason
import ai.cargo.geometry.PathVerdict
import ai.cargo.geometry.SceneGeometry
import ai.cargo.geometry.SceneJudge
import ai.cargo.geometry.SceneJudgement
import ai.cargo.geometry.SearchBudget
import ai.cargo.geometry.SolveOutcome

sealed interface ValidationOutcome {
    /** The caller dispatches this back through ApplyValidation, where a late result is rejected. */
    data class Ready(val record: ValidationRecord, val geometry: SceneGeometry) : ValidationOutcome

    /** No snapshot exists, so no confirmed revision can be created from this. */
    data class NotAvailable(val reason: NotAvailableReason, val detail: String) : ValidationOutcome
}

object SceneValidator {
    fun currentHash(state: SceneDraftState, context: SceneContext): String? {
        val profile = context.profile(state.profileId, state.profileRevision) ?: return null
        return InputHash.of(state.instances, profile, context)
    }

    /** Instances the draft placed or moved. Only these claim a checked route; unchanged items stay as they were. */
    fun changedFromBase(state: SceneDraftState): List<Instance> {
        val base = state.baseInstances.associateBy { it.instanceId }
        return state.instances.filter { instance ->
            val before = base[instance.instanceId]
            before == null || before.poseMm != instance.poseMm || before.orientationIndex != instance.orientationIndex
        }
    }

    fun validate(state: SceneDraftState, context: SceneContext): ValidationOutcome {
        val profile = context.profile(state.profileId, state.profileRevision)
            ?: return ValidationOutcome.NotAvailable(NotAvailableReason.PROFILE_INVALID, "Profile version not available")
        val placed = context.placed(state.instances)
            ?: return ValidationOutcome.NotAvailable(NotAvailableReason.ITEM_VERSION_MISSING, "Referenced item version not available")
        val hash = InputHash.of(state.instances, profile, context)
            ?: return ValidationOutcome.NotAvailable(NotAvailableReason.ITEM_VERSION_MISSING, "Referenced item version not available")
        val subjects = changedFromBase(state).map { it.instanceId }
        return when (val judged = SceneJudge.judge(placed, profile, subjects, context.config)) {
            is SceneJudgement.NotAvailable -> ValidationOutcome.NotAvailable(judged.reason, judged.detail)
            is SceneJudgement.Judged -> ValidationOutcome.Ready(
                ValidationRecord(
                    inputHash = hash,
                    engineVersion = context.config.engineVersion,
                    configurationRevision = context.config.revision,
                    snapshot = snapshot(judged.geometry, hash, applicableSearch(state, context)),
                    issues = issues(judged.geometry),
                ),
                judged.geometry,
            )
        }
    }

    /** A search result only speaks for the fixed items it ran against. */
    fun applicableSearch(state: SceneDraftState, context: SceneContext): SearchResult {
        val record = state.lastSearch ?: return SearchResult(SearchStatus.NOT_RUN)
        val profile = context.profile(state.profileId, state.profileRevision) ?: return SearchResult(SearchStatus.NOT_RUN)
        val fixed = state.instances.filter { it.instanceId != record.subjectInstanceId }
        val fingerprint = InputHash.searchFingerprint(fixed, profile, record.item, context)
        return if (fingerprint == record.fingerprint) record.wire else SearchResult(SearchStatus.NOT_RUN)
    }

    private fun snapshot(g: SceneGeometry, hash: String, search: SearchResult) = JudgementSnapshot(
        placement = g.placement.status,
        opening = g.opening,
        path = when (g.path) {
            PathVerdict.VERIFIED_IN_MODEL -> PathResult(g.path.name, GeometryConfig.PATH_CLASS)
            else -> PathResult(g.path.name)
        },
        handling = g.handling,
        search = search,
        minimumClearanceMm = g.placement.minimumClearanceMm,
        errorMm = g.errorMm,
        conflictLocations = g.placement.issues.map { it.location }.distinct().take(100),
        staleReasons = emptyList(),
        inputHash = hash,
        safety = g.safety,
    )

    private fun issues(g: SceneGeometry): List<JudgementIssue> {
        val conflicts = g.placement.issues.filter { it.status == PlacementStatus.CONFLICT }
            .map { JudgementIssue(IssueKind.PLACEMENT_CONFLICT, it.instanceId) }
        val handling = g.handlingIssues.map { JudgementIssue(it.kind.toIssueKind(), it.instanceId) }
        return (conflicts + handling).distinct()
    }

    private fun HandlingIssueKind.toIssueKind(): IssueKind = when (this) {
        HandlingIssueKind.ROTATION_NOT_ALLOWED -> IssueKind.ROTATION_NOT_ALLOWED
        HandlingIssueKind.AIRBORNE -> IssueKind.AIRBORNE
        HandlingIssueKind.INSUFFICIENT_SUPPORT -> IssueKind.INSUFFICIENT_SUPPORT
        HandlingIssueKind.ON_NON_STACKABLE -> IssueKind.ON_NON_STACKABLE
    }
}

sealed interface SearchRun {
    /** Dispatch [record] through FinishSearch; [outcome] carries the candidate pose for the user to accept. */
    data class Done(val outcome: SolveOutcome.Completed, val record: SearchRecord) : SearchRun

    data class NotAvailable(val reason: NotAvailableReason, val detail: String) : SearchRun
}

object SceneSearch {
    /** Every instance currently in the draft is fixed for this search; only a new item's pose can come back. */
    fun run(
        state: SceneDraftState,
        item: ItemRef,
        context: SceneContext,
        budget: SearchBudget = SearchBudget.DEFAULT,
        cancel: CancelSignal = CancelSignal.NEVER,
    ): SearchRun {
        val profile = context.profile(state.profileId, state.profileRevision)
            ?: return SearchRun.NotAvailable(NotAvailableReason.PROFILE_INVALID, "Profile version not available")
        val fixed = context.placed(state.instances)
            ?: return SearchRun.NotAvailable(NotAvailableReason.ITEM_VERSION_MISSING, "Referenced item version not available")
        val candidate = context.item(item)
            ?: return SearchRun.NotAvailable(NotAvailableReason.ITEM_VERSION_MISSING, "Candidate item version not available")
        val fingerprint = InputHash.searchFingerprint(state.instances, profile, item, context)
            ?: return SearchRun.NotAvailable(NotAvailableReason.ITEM_VERSION_MISSING, "Referenced item version not available")
        return when (val outcome = CandidateSearch.solveNewItem(fixed, candidate, profile, budget, cancel, context.config)) {
            is SolveOutcome.NotAvailable -> SearchRun.NotAvailable(outcome.reason, outcome.detail)
            is SolveOutcome.Completed -> SearchRun.Done(
                outcome,
                SearchRecord(
                    status = outcome.search.status,
                    reason = outcome.search.reason,
                    item = item,
                    fingerprint = fingerprint,
                    interruptCause = when (outcome.details.interruptCause) {
                        InterruptCause.BUDGET -> SearchInterruptCause.BUDGET
                        InterruptCause.CANCELLED -> SearchInterruptCause.CANCELLED
                        null -> null
                    },
                ),
            )
        }
    }
}
