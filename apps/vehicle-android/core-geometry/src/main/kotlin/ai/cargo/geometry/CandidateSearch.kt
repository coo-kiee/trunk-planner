package ai.cargo.geometry

import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.SearchResult
import ai.cargo.contracts.SearchStatus
import ai.cargo.contracts.UncertifiedReason
import ai.cargo.contracts.Vec3Mm

/** Output of the solver. It has room for one pose of the new item only, so a fixed pose cannot be returned. */
data class CandidatePose(val poseMm: Vec3Mm, val orientationIndex: Long)

/** Limit on evaluated candidate poses, chosen so a run can be reproduced exactly from the count. */
data class SearchBudget(val maxChecks: Long) {
    companion object { val DEFAULT = SearchBudget(200_000) }
}

fun interface CancelSignal {
    fun isCancelled(): Boolean

    companion object { val NEVER = CancelSignal { false } }
}

enum class InterruptCause { BUDGET, CANCELLED }

/** Local detail beyond the wire SearchResult. Recomputed, never added to the wire schema. */
data class SearchDetails(
    val checkedCandidates: Long,
    val collisionFreeCandidateSeen: Boolean,
    /** All candidates ran out given fixed poses. This never claims that moving the fixed items would not help. */
    val exhaustedAgainstFixedPoses: Boolean,
    val interruptCause: InterruptCause?,
    val placementAtCandidate: PlacementStatus?,
)

sealed interface SolveOutcome {
    data class Completed(val search: SearchResult, val pose: CandidatePose?, val details: SearchDetails) : SolveOutcome

    data class NotAvailable(val reason: NotAvailableReason, val detail: String) : SolveOutcome
}

/**
 * Finite search for one new item among fixed instances.
 *
 * Proof boundary. CERTIFIED_INFEASIBLE_IN_MODEL holds only for: no fixed instance, one BOX proxy, static AABB
 * obstacles of the profile, the permitted orientations, and collision alone, evaluated at best bounds. Argument:
 * for one orientation with best extent D, half-open obstacle [a,b) forbids exactly the open interval (a-D, b) on
 * its axis, so the feasible set is the closed box [L,B+es-D] minus a union of open boxes, where L is the best
 * lower wall (-es on x and z, 0 on the floor). Pushing any feasible point down each axis in turn stops at L or at
 * some obstacle maximum b. Those values are all in the candidate grid, so an empty grid result means an empty
 * feasible set in this model.
 *
 * That argument says nothing about support, composite shapes or other items, so those end as
 * NO_CANDIDATE_UNCERTIFIED with an exact reason. Rearranging fixed items is never considered. Neither result is
 * a statement about the real vehicle. SEARCH_INTERRUPTED means only that the budget or a cancel stopped the run.
 */
object CandidateSearch {
    fun solveNewItem(
        fixed: List<PlacedInput>,
        candidate: ItemVersion,
        profile: ProfileVersion,
        budget: SearchBudget = SearchBudget.DEFAULT,
        cancel: CancelSignal = CancelSignal.NEVER,
        config: GeometryConfig = GeometryConfig.V1,
    ): SolveOutcome = try {
        val space = ProfileSpace.of(profile, config)
        if (fixed.size + 1 > config.maxInstances) {
            throw NotAvailableException(NotAvailableReason.INSTANCE_LIMIT_EXCEEDED, "${fixed.size} fixed instances")
        }
        Search(fixed.map { PlacedGeometry.of(it, config) }, ItemGeometry.of(candidate, config), space, budget, cancel, config).run()
    } catch (error: NotAvailableException) {
        SolveOutcome.NotAvailable(error.reason, error.detail)
    }
}

private class Search(
    private val fixed: List<PlacedGeometry>,
    private val item: ItemGeometry,
    private val space: ProfileSpace,
    private val budget: SearchBudget,
    private val cancel: CancelSignal,
    private val config: GeometryConfig,
) {
    private var checks = 0L
    private var collisionFree = false
    private var nominalFallback: CandidatePose? = null
    private var bestFallback: CandidatePose? = null

    private val solidWorst = fixed.flatMap { it.boxes(Bound.WORST, config.marginMm) } + space.obstacles(Bound.WORST)
    private val solidNominal = fixed.flatMap { it.boxes(Bound.NOMINAL) } + space.obstacles(Bound.NOMINAL)
    private val solidBest = fixed.flatMap { it.boxes(Bound.BEST) } + space.obstacles(Bound.BEST)

    fun run(): SolveOutcome.Completed {
        for (index in item.allowedOrientations()) {
            val shape = item.oriented(index)
            val grid = CandidateGrid(shape, item.errorMm, space, solidWorst + solidNominal + solidBest)
            // Low first, then deep inside, then toward the vehicle right; a stable preference, not an optimisation.
            for (y in grid.ys) for (z in grid.zs) for (x in grid.xs) {
                if (cancel.isCancelled()) return interrupted(InterruptCause.CANCELLED)
                if (checks >= budget.maxChecks) return interrupted(InterruptCause.BUDGET)
                checks++
                val pose = Vec(x, y, z)
                if (evaluate(shape, pose)) return found(shape, pose, PlacementStatus.CLEAR)
            }
        }
        nominalFallback?.let { return found(it, PlacementStatus.UNCERTAIN) }
        bestFallback?.let { return found(it, PlacementStatus.UNCERTAIN) }
        return exhausted()
    }

    /** True when the pose clears even at worst bounds; weaker passes are remembered as fallbacks. */
    private fun evaluate(shape: OrientedShape, pose: Vec): Boolean {
        if (!clear(shape, pose, Bound.BEST, solidBest)) return false
        collisionFree = true
        val support = evaluateSupport(shape, pose, CANDIDATE_ID, fixed, space, config)
        if (!support.meetsPolicy || support.onNonStackable.isNotEmpty()) return false
        if (clear(shape, pose, Bound.WORST, solidWorst)) return true
        val candidate = CandidatePose(Vec3Mm(pose.x, pose.y, pose.z), shape.orientationIndex.toLong())
        if (nominalFallback == null && clear(shape, pose, Bound.NOMINAL, solidNominal)) nominalFallback = candidate
        if (bestFallback == null) bestFallback = candidate
        return false
    }

    private fun clear(shape: OrientedShape, pose: Vec, bound: Bound, solid: List<Box>): Boolean {
        val margin = if (bound == Bound.WORST) config.marginMm else 0L
        val boxes = shape.boxesAt(pose, bound, item.errorMm, margin)
        val cargo = space.space(bound)
        return boxes.all { box -> box.isInside(cargo) && solid.none(box::overlaps) }
    }

    private fun exhausted(): SolveOutcome.Completed {
        val hasFixed = fixed.isNotEmpty()
        val result = when {
            !item.isSingleBox -> uncertified(UncertifiedReason.PROOF_CLASS_UNSUPPORTED)
            hasFixed -> uncertified(UncertifiedReason.MULTI_ITEM_PROOF_UNSUPPORTED)
            collisionFree -> uncertified(UncertifiedReason.SUPPORT_CONSTRAINT_UNPROVEN)
            else -> SearchResult(SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL)
        }
        return SolveOutcome.Completed(result, null, details(exhaustedAgainstFixed = hasFixed, cause = null, placement = null))
    }

    private fun uncertified(reason: UncertifiedReason) = SearchResult(SearchStatus.NO_CANDIDATE_UNCERTIFIED, reason)

    private fun interrupted(cause: InterruptCause) = SolveOutcome.Completed(
        SearchResult(SearchStatus.SEARCH_INTERRUPTED),
        null,
        details(exhaustedAgainstFixed = false, cause = cause, placement = null),
    )

    private fun found(shape: OrientedShape, pose: Vec, placement: PlacementStatus) =
        found(CandidatePose(Vec3Mm(pose.x, pose.y, pose.z), shape.orientationIndex.toLong()), placement)

    private fun found(pose: CandidatePose, placement: PlacementStatus) = SolveOutcome.Completed(
        SearchResult(SearchStatus.FOUND_PLACEMENT),
        pose,
        details(exhaustedAgainstFixed = false, cause = null, placement = placement),
    )

    private fun details(exhaustedAgainstFixed: Boolean, cause: InterruptCause?, placement: PlacementStatus?) =
        SearchDetails(checks, collisionFree, exhaustedAgainstFixed, cause, placement)

    private companion object { const val CANDIDATE_ID = "candidate" }
}

/** Boundary and obstacle-face coordinates per axis, over the nominal, worst and best extents of every proxy. */
private class CandidateGrid(shape: OrientedShape, errorMm: Long, space: ProfileSpace, solid: List<Box>) {
    val xs = axis(shape, errorMm, space, solid, { it.minX }, { it.maxX }, space.bounds.x, wallError = true).sorted()
    val ys = axis(shape, errorMm, space, solid, { it.minY }, { it.maxY }, space.bounds.y, wallError = false).sorted()
    val zs = axis(shape, errorMm, space, solid, { it.minZ }, { it.maxZ }, space.bounds.z, wallError = true).sortedDescending()

    private fun axis(
        shape: OrientedShape,
        errorMm: Long,
        space: ProfileSpace,
        solid: List<Box>,
        low: (Box) -> Long,
        high: (Box) -> Long,
        bound: Long,
        wallError: Boolean,
    ): List<Long> {
        val origin = Vec(0, 0, 0)
        val parts = listOf(Bound.BEST, Bound.NOMINAL, Bound.WORST).flatMap { shape.boxesAt(origin, it, errorMm) }
        // The lower wall at each bound: -es best, 0 nominal, +es worst; the floor never moves.
        val lowest = if (wallError) -space.errorMm else 0L
        val values = HashSet<Long>()
        values += listOf(0L, lowest, -lowest)
        for (part in parts) {
            values += bound + space.errorMm - high(part)
            values += bound - high(part)
            values += bound - space.errorMm - high(part)
            for (box in solid) {
                values += high(box) - low(part)
                values += low(box) - high(part)
            }
        }
        val smallestReach = parts.minOf(high)
        return values.filter { it >= lowest && it + smallestReach <= bound + space.errorMm }
    }
}
