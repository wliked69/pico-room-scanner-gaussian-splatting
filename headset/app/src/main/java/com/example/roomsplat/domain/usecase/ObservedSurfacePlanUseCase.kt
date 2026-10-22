package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3

/** Builds a keyed geometry snapshot off the UI thread. Evidence stays owned by the caller. */
class ObservedSurfacePlanUseCase<K> {
    data class Anchor<K>(val id: K, val points: List<Point3>, val keys: List<String>)
    data class Plan<K>(val points: Map<String,Point3>, val owners: Map<String,Set<K>>)
    fun build(anchors: List<Anchor<K>>): Plan<K> {
        val points=linkedMapOf<String,Point3>()
        val owners=mutableMapOf<String,MutableSet<K>>()
        for(anchor in anchors) {
            require(anchor.points.size==anchor.keys.size)
            for(i in anchor.keys.indices) {
                val key=anchor.keys[i]
                points[key]=anchor.points[i]
                owners.getOrPut(key) { mutableSetOf() }.add(anchor.id)
            }
        }
        return Plan(points,owners)
    }
}
