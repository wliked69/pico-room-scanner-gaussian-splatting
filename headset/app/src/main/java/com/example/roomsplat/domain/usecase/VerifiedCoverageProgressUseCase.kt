package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.model.SurfaceRecord
import kotlin.math.floor

/** Stable, bounded scan progress. Native provider/remesh counts never enter this registry.
 * New discoveries are canonical points; nearby aliases cannot extend/propagate the match radius.
 */
class VerifiedCoverageProgressUseCase(private val capacity:Int=80_000) {
    private data class Region(val point:Point3,var status:SurfaceRecord=SurfaceRecord.UNRECORDED)
    private data class Bucket(val x:Int,val y:Int,val z:Int)
    private val regions=linkedMapOf<String,Region>()
    private val buckets=mutableMapOf<Bucket,MutableList<String>>()
    private fun bucket(p:Point3)=Bucket(floor(p.x/.03f).toInt(),floor(p.y/.03f).toInt(),floor(p.z/.03f).toInt())
    private fun find(key:String,p:Point3):Region? {
        regions[key]?.let { return it }
        val b=bucket(p)
        for(x in b.x-1..b.x+1)for(y in b.y-1..b.y+1)for(z in b.z-1..b.z+1)
            for(id in buckets[Bucket(x,y,z)].orEmpty()) {
                val region=regions.getValue(id);val d=region.point-p
                if(d.dot(d)<=.03f*.03f)return region
            }
        return null
    }
    fun discover(verified:Map<String,Point3>) {
        verified.forEach { (key,p)->
            if(regions.size<capacity && find(key,p)==null) {
                regions[key]=Region(p);buckets.getOrPut(bucket(p)) { mutableListOf() }.add(key)
            }
        }
    }
    /** Call only from the successfully saved-frame path, never from geometry reconciliation. */
    fun recordSaved(changes:Map<String,Pair<Point3,SurfaceRecord>>) {
        changes.forEach { (key,value)->find(key,value.first)?.let { region ->
            if(value.second.ordinal>region.status.ordinal)region.status=value.second
        } }
    }
    fun resetPhotos() { regions.values.forEach { it.status=SurfaceRecord.UNRECORDED } }
    fun clear() { regions.clear();buckets.clear() }
    fun counts()=Triple(regions.size,regions.values.count { it.status!=SurfaceRecord.UNRECORDED },
        regions.values.count { it.status==SurfaceRecord.MULTIPLE_POSITIONS })
    fun records():List<Pair<Point3,SurfaceRecord>> = regions.values.map { it.point to it.status }
}
