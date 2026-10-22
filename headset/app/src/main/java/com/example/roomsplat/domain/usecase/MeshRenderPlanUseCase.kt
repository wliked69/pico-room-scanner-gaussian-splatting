
package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.SurfaceRecord

/** Immutable draw plans computed off the UI thread; compact each group's vertex references without changing geometry. */
class MeshRenderPlanUseCase {
    data class Group(val globalIndices: List<Int>, val vertexSources: List<Int>, val localIndices: List<Int>)
    fun build(indices: List<Int>, states: ByteArray, vertexCount: Int): Map<SurfaceRecord,Group> {
        require(indices.size==states.size*3 && indices.all { it in 0 until vertexCount })
        val grouped=Array(SurfaceRecord.entries.size) { mutableListOf<Int>() }
        for(t in states.indices) {
            val state=states[t].toInt()
            if(state<0)continue
            require(state<grouped.size)
            grouped[state].add(indices[t*3]);grouped[state].add(indices[t*3+1]);grouped[state].add(indices[t*3+2])
        }
        return SurfaceRecord.entries.associateWith { status ->
            val source=grouped[status.ordinal]
            val remap=IntArray(vertexCount) { -1 }
            val vertices=mutableListOf<Int>();val local=ArrayList<Int>(source.size)
            for(index in source) {
                var slot=remap[index]
                if(slot<0) { slot=vertices.size;vertices.add(index);remap[index]=slot }
                local.add(slot)
            }
            Group(source,vertices,local)
        }
    }
}
