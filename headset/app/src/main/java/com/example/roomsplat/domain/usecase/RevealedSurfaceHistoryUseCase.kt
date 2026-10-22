package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.floor

/** Display progress only. Never supplies geometry, photograph evidence or a room identity.
 * Nearby inheritance is anchored to directly revealed samples; inherited points are not
 * remembered again, so successive mesh revisions cannot flood progress across a wall.
 */
class RevealedSurfaceHistoryUseCase(private val capacity:Int=80_000) {
    enum class Match { EXACT, NEARBY }
    private data class Bucket(val x:Int,val y:Int,val z:Int)
    private val points=linkedMapOf<String,Point3>()
    private val buckets=mutableMapOf<Bucket,MutableSet<String>>()
    init { require(capacity>0) }
    val size:Int get()=points.size
    fun clear() { points.clear();buckets.clear() }
    private fun bucket(p:Point3)=Bucket(floor(p.x/TOLERANCE_M).toInt(),
        floor(p.y/TOLERANCE_M).toInt(),floor(p.z/TOLERANCE_M).toInt())

    fun remember(key:String,point:Point3) {
        if(key in points)return
        points[key]=point
        buckets.getOrPut(bucket(point)) { mutableSetOf() }.add(key)
        if(points.size>capacity) {
            val iterator=points.entries.iterator();val oldest=iterator.next()
            val oldKey=oldest.key;val oldBucket=bucket(oldest.value)
            iterator.remove()
            buckets[oldBucket]?.let { ids -> ids.remove(oldKey);if(ids.isEmpty())buckets.remove(oldBucket) }
        }
    }
    fun match(key:String,point:Point3):Match? {
        if(key in points)return Match.EXACT
        if(points.isEmpty())return null
        val b=bucket(point)
        for(x in b.x-1..b.x+1)for(y in b.y-1..b.y+1)for(z in b.z-1..b.z+1) {
            for(candidate in buckets[Bucket(x,y,z)].orEmpty()) {
                val delta=point-checkNotNull(points[candidate])
                if(delta.dot(delta)<=TOLERANCE_M*TOLERANCE_M)return Match.NEARBY
            }
        }
        return null
    }
    companion object { const val TOLERANCE_M=.03f }
}
