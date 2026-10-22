package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.LiveDepthFrame
import com.example.roomsplat.domain.model.Point3

/** Pure prepared evidence; caller still validates current pose/time/scan epoch before applying it. */
class LiveDepthQueryCacheUseCase {
    data class Result(val blocked:Set<String>,val visibility:Map<String,LiveDepthAdmissionUseCase.Visibility>)
    private data class Entry(val generation:Long,val points:Map<String,Point3>,val frame:LiveDepthFrame,val result:Result)
    @Volatile private var generation=0L
    @Volatile private var entry:Entry?=null
    var hits=0L;private set
    var builds=0L;private set
    fun clear() { generation++;entry=null }
    fun evaluate(points:Map<String,Point3>,frame:LiveDepthFrame):Result {
        val epoch=generation
        entry?.takeIf { it.generation==epoch && it.points===points && it.frame===frame }?.let { hits++;return it.result }
        builds++
        val query=LiveDepthAdmissionUseCase()
        val visibility=points.mapValues { query.visibility(it.value,frame) }
        val result=Result(visibility.filterValues { it!=LiveDepthAdmissionUseCase.Visibility.MATCHED }.keys,visibility)
        // Even if clear races this publication, lookup rejects the retired generation.
        entry=Entry(epoch,points,frame,result)
        return result
    }
}
