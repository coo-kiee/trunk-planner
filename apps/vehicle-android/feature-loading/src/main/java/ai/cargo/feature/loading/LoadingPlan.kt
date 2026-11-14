package ai.cargo.feature.loading

import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.SceneRevision
import ai.cargo.geometry.PlacedInput
import ai.cargo.geometry.RemovalVerdict
import ai.cargo.geometry.SceneJudge
import ai.cargo.geometry.SceneJudgement
import ai.cargo.scene.SceneContext

data class ChecklistRow(
    val instanceId: String,
    val name: String,
    val dimensions: String,
    val poseMm: String,
    val locked: Boolean,
    val removalPriority: Long,
    val removal: RemovalVerdict?,
    val blockedBy: List<String>,
)

data class LoadingPlan(val sceneRevision: Long, val loadOrder: List<ChecklistRow>, val unloadOrder: List<ChecklistRow>, val available: Boolean)

/** Pure ordering. Items that come out last go in first; the reverse-sweep warning comes from the geometry engine. */
object LoadingPlanner {
    fun plan(revision: SceneRevision, context: SceneContext): LoadingPlan {
        val placed: List<PlacedInput>? = context.placed(revision.instances)
        val profile: ProfileVersion? = context.profile(revision.profileId, revision.profileRevision)
        if (placed == null || profile == null) return LoadingPlan(revision.revision, emptyList(), emptyList(), available = false)
        val judged = SceneJudge.judge(placed, profile, emptyList(), context.config) as? SceneJudgement.Judged
        val removal = judged?.geometry?.removal.orEmpty().associateBy { it.instanceId }
        val names = placed.associate { it.instanceId to it.item.name }
        val rows = placed.map { input ->
            val detail = removal[input.instanceId]
            val d = input.item.measuredDimensionsMm
            ChecklistRow(
                instanceId = input.instanceId,
                name = input.item.name,
                dimensions = "${d.x}×${d.y}×${d.z} mm",
                poseMm = "x ${input.poseMm.x} · y ${input.poseMm.y} · z ${input.poseMm.z}",
                locked = revision.instances.first { it.instanceId == input.instanceId }.locked,
                removalPriority = input.item.removalPriority,
                removal = detail?.verdict,
                blockedBy = detail?.blockedBy.orEmpty().mapNotNull(names::get),
            )
        }
        val load = rows.sortedByDescending { it.removalPriority }
        return LoadingPlan(revision.revision, load, load.reversed(), available = true)
    }
}
