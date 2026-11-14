package ai.cargo.geometry

import ai.cargo.contracts.HandlingStatus
import ai.cargo.contracts.OpeningStatus
import ai.cargo.contracts.ProfileVersion

enum class HandlingIssueKind { ROTATION_NOT_ALLOWED, AIRBORNE, INSUFFICIENT_SUPPORT, ON_NON_STACKABLE }

data class HandlingIssue(val instanceId: String, val kind: HandlingIssueKind)

/** Independent axes. Nothing here combines them into an overall "fits", and safety is never judged. */
data class SceneGeometry(
    val placement: PlacementDetail,
    val opening: OpeningStatus,
    val openingDetails: List<OpeningDetail>,
    val path: PathVerdict,
    val pathDetails: List<PathDetail>,
    val handling: HandlingStatus,
    val handlingIssues: List<HandlingIssue>,
    val support: List<SupportDetail>,
    val removal: List<RemovalDetail>,
    val errorMm: Long,
) {
    val safety: String get() = GeometryConfig.SAFETY_NOT_ASSESSED
}

sealed interface SceneJudgement {
    data class Judged(val geometry: SceneGeometry) : SceneJudgement

    /** No geometry may be derived, so callers cannot create a snapshot or a confirmed revision from this. */
    data class NotAvailable(val reason: NotAvailableReason, val detail: String) : SceneJudgement
}

object SceneJudge {
    const val DEFAULT_PATH_CHECKS = 2_000_000L

    /**
     * Judges an arrangement without changing it. [pathSubjects] lists instances whose route is checked, in load
     * order: an earlier subject and every non-subject block a later subject. Instances not listed stay unverified.
     */
    fun judge(
        instances: List<PlacedInput>,
        profile: ProfileVersion,
        pathSubjects: List<String>,
        config: GeometryConfig = GeometryConfig.V1,
        maxPathChecks: Long = DEFAULT_PATH_CHECKS,
    ): SceneJudgement = try {
        SceneJudgement.Judged(judgeValidated(instances, profile, pathSubjects, config, maxPathChecks))
    } catch (error: NotAvailableException) {
        SceneJudgement.NotAvailable(error.reason, error.detail)
    }

    private fun judgeValidated(
        instances: List<PlacedInput>,
        profile: ProfileVersion,
        pathSubjects: List<String>,
        config: GeometryConfig,
        maxPathChecks: Long,
    ): SceneGeometry {
        val space = ProfileSpace.of(profile, config)
        if (instances.size > config.maxInstances) {
            throw NotAvailableException(NotAvailableReason.INSTANCE_LIMIT_EXCEEDED, "${instances.size} instances")
        }
        val placed = instances.map { PlacedGeometry.of(it, config) }
        val openingDetails = placed.map { judgeOpening(it, space, config) }
        val pathDetails = judgePaths(placed, pathSubjects, space, config, maxPathChecks)
        val support = placed.map { item ->
            evaluateSupport(item.shape, item.pose, item.id, placed.filterNot { it === item }, space, config)
        }
        val handlingIssues = placed.flatMap(::rotationIssue) + support.flatMap(::supportIssues)
        return SceneGeometry(
            placement = judgePlacement(placed, space, config),
            opening = aggregateOpening(openingDetails, space),
            openingDetails = openingDetails,
            path = aggregatePath(pathDetails),
            pathDetails = pathDetails,
            handling = if (handlingIssues.isEmpty()) HandlingStatus.OK else HandlingStatus.VIOLATION,
            handlingIssues = handlingIssues,
            support = support,
            removal = placed.map { item -> evaluateRemoval(item, placed.filterNot { it === item }, config) },
            errorMm = minOf(
                GeometryConfig.SNAPSHOT_ERROR_MAX_MM,
                (placed.maxOfOrNull { it.item.errorMm } ?: 0L) + space.errorMm,
            ),
        )
    }

    private fun judgePaths(
        placed: List<PlacedGeometry>,
        subjects: List<String>,
        space: ProfileSpace,
        config: GeometryConfig,
        maxPathChecks: Long,
    ): List<PathDetail> {
        val sweep = PathSweep(space, config, maxPathChecks)
        val byId = placed.associateBy { it.id }
        return subjects.mapIndexedNotNull { index, id ->
            val subject = byId[id] ?: return@mapIndexedNotNull null
            val notYetLoaded = subjects.drop(index + 1).toSet()
            sweep.check(subject, placed.filter { it.id != id && it.id !in notYetLoaded })
        }
    }

    private fun aggregateOpening(details: List<OpeningDetail>, space: ProfileSpace): OpeningStatus = when {
        space.opening == null -> OpeningStatus.OPENING_UNMEASURED
        // An empty scene has nothing that was shown to pass.
        details.isEmpty() -> OpeningStatus.UNCERTAIN
        details.any { it.status == OpeningStatus.DOES_NOT_FIT } -> OpeningStatus.DOES_NOT_FIT
        details.any { it.status == OpeningStatus.UNCERTAIN } -> OpeningStatus.UNCERTAIN
        else -> OpeningStatus.FITS
    }

    private fun aggregatePath(details: List<PathDetail>): PathVerdict = when {
        details.isEmpty() -> PathVerdict.UNVERIFIED
        details.any { it.verdict == PathVerdict.NOT_FOUND_IN_CHECKED_CLASS } -> PathVerdict.NOT_FOUND_IN_CHECKED_CLASS
        details.any { it.verdict == PathVerdict.UNVERIFIED } -> PathVerdict.UNVERIFIED
        else -> PathVerdict.VERIFIED_IN_MODEL
    }

    private fun rotationIssue(item: PlacedGeometry): List<HandlingIssue> =
        if (item.shape.orientationIndex in item.item.allowedOrientations()) {
            emptyList()
        } else {
            listOf(HandlingIssue(item.id, HandlingIssueKind.ROTATION_NOT_ALLOWED))
        }

    private fun supportIssues(detail: SupportDetail): List<HandlingIssue> = buildList {
        detail.onNonStackable.forEach { _ -> add(HandlingIssue(detail.instanceId, HandlingIssueKind.ON_NON_STACKABLE)) }
        if (!detail.meetsPolicy) {
            val kind = if (detail.supportedAreaMm2 == 0L) HandlingIssueKind.AIRBORNE else HandlingIssueKind.INSUFFICIENT_SUPPORT
            add(HandlingIssue(detail.instanceId, kind))
        }
    }.distinct()
}
