package ai.cargo.feature.editor

import ai.cargo.geometry.ItemGeometry
import ai.cargo.render.RenderAabb
import ai.cargo.render.RenderCamera
import ai.cargo.render.RenderInstance
import ai.cargo.render.RenderRole
import ai.cargo.render.RenderScene
import ai.cargo.scene.IssueKind
import ai.cargo.scene.StaleAnalysis
import ai.cargo.scene.itemRef

/** Geometry to drawing, one way. The renderer stays unaware of the engine, and nothing comes back from it. */
object SceneRenderMapper {
    fun scene(state: EditorUiState): RenderScene {
        val draft = state.draft ?: return RenderScene.EMPTY
        val context = state.context ?: return RenderScene.EMPTY
        val profile = state.profile
        val current = StaleAnalysis.forDraft(draft, context).isEmpty()
        val conflicts = draft.validation?.takeIf { current }?.issues.orEmpty()
            .filter { it.kind == IssueKind.PLACEMENT_CONFLICT }.map { it.instanceId }.toSet()
        val instances = draft.instances.mapNotNull { instance ->
            val item = context.item(instance.itemRef) ?: return@mapNotNull null
            val shape = runCatching { ItemGeometry.of(item, context.config).oriented(instance.orientationIndex.toInt()) }.getOrNull()
                ?: return@mapNotNull null
            val p = instance.poseMm
            RenderInstance(
                id = instance.instanceId,
                boxes = shape.boxes.map { b ->
                    RenderAabb(
                        (b.minX + p.x).toFloat(), (b.minY + p.y).toFloat(), (b.minZ + p.z).toFloat(),
                        (b.maxX + p.x).toFloat(), (b.maxY + p.y).toFloat(), (b.maxZ + p.z).toFloat(),
                    )
                },
                role = when {
                    instance.instanceId == state.selected -> RenderRole.SELECTED
                    instance.instanceId in conflicts -> RenderRole.CONFLICT
                    !current -> RenderRole.UNVERIFIED
                    else -> RenderRole.ITEM
                },
            )
        }
        return RenderScene(
            space = profile?.bounds?.let { RenderAabb(0f, 0f, 0f, it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) },
            obstacles = profile?.obstacles.orEmpty().map {
                RenderAabb(
                    it.origin.x.toFloat(), it.origin.y.toFloat(), it.origin.z.toFloat(),
                    (it.origin.x + it.size.x).toFloat(), (it.origin.y + it.size.y).toFloat(), (it.origin.z + it.size.z).toFloat(),
                )
            },
            instances = instances,
        )
    }

    fun camera(state: EditorUiState): RenderCamera? = state.draft?.camera?.let {
        RenderCamera(it.target.x.toFloat(), it.target.y.toFloat(), it.target.z.toFloat(), it.yawDegrees.toFloat(), it.pitchDegrees.toFloat(), it.distanceMm.toFloat(), EditorRules.isFitCamera(it, state.profile))
    }
}
