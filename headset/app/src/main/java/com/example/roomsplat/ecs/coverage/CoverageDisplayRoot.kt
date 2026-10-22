package com.example.roomsplat.ecs.coverage

import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.EntityQueryCondition
import com.pico.spatial.core.ecs.ModelComponent

/** Owns the entire display tree, including children that are absent from the geometry cache. */
class CoverageDisplayRoot {
    var entity: Entity = createRoot()
        private set
    private var content: SpatialViewContent? = null

    data class Retirement(val rootId: Long, val descendants: Int, val models: Int,
        val destroyed: Boolean, val remainingIds: List<Long>)

    fun attach(next: SpatialViewContent) {
        if (content === next) return
        content?.removeEntity(entity)
        content = next
        next.addEntity(entity)
    }

    fun setVisible(value: Boolean) {
        // Also set the owned descendants explicitly so a renderer cannot retain an enabled old model.
        tree(entity).forEach { it.enabled = value }
    }

    fun replace(visible: Boolean): Retirement {
        val previous = entity
        val retirement = retire(previous)
        entity = createRoot().apply { enabled = visible }
        content?.addEntity(entity)
        return retirement
    }

    fun close(): Retirement {
        val retirement = retire(entity)
        content = null
        return retirement
    }

    fun diagnostic(): Map<String, Any> {
        val nodes = tree(entity)
        val models = nodes.filter { it.components[ModelComponent::class.java] != null }
        val roots = entity.scene?.queryEntity(EntityQueryCondition.customCondition {
            it.getName() == ROOT_NAME
        }).orEmpty()
        return mapOf("root_id" to entity.id, "root_enabled" to entity.enabled,
            "descendants" to nodes.size - 1, "models" to models.size,
            "enabled_models" to models.count { it.enabled },
            "content_roots" to (content?.entities?.count { it.getName() == ROOT_NAME } ?: 0),
            "scene_root_ids" to roots.map { it.id },
            "scene_roots" to roots.map { root ->
                val rootModels=tree(root).filter { it.components[ModelComponent::class.java]!=null }
                mapOf("id" to root.id,"enabled" to root.enabled,"models" to rootModels.size,
                    "enabled_models" to rootModels.count { it.enabled })
            })
    }

    private fun retire(previous: Entity): Retirement {
        val scene = previous.scene
        val nodes = tree(previous)
        val ids = nodes.map { it.id }
        val models = nodes.count { it.components[ModelComponent::class.java] != null }
        nodes.forEach { it.enabled = false }
        content?.removeEntity(previous)
        val destroyed = previous.destroy()
        return Retirement(ids.first(), nodes.size - 1, models, destroyed,
            if (scene == null) emptyList() else ids.filter { scene.findById(it) != null })
    }

    private fun tree(root: Entity): List<Entity> {
        val nodes = mutableListOf<Entity>()
        val pending = ArrayDeque<Entity>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            nodes.add(node)
            pending.addAll(node.getChildren())
        }
        return nodes
    }

    private fun createRoot() = Entity().apply { setName(ROOT_NAME) }

    companion object { private const val ROOT_NAME = "live_mesh_root" }
}
