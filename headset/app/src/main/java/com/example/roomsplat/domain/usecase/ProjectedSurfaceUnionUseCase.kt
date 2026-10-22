package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.usecase.RegularPlaneMeshUseCase.UV
import kotlin.math.abs

/** Exact union support via convex subtraction; overlapping owners cannot accidentally close a hole. */
class ProjectedSurfaceUnionUseCase {
    fun covers(triangle:List<UV>,faces:List<List<UV>>):Boolean {
        val wanted=area(triangle)
        if(wanted<1e-12)return false
        return uncovered(triangle,faces).sumOf(::area)<=maxOf(1e-8,wanted*1e-5)
    }
    /** Convex pieces of the original triangle outside the measured polygon union. */
    fun uncovered(triangle:List<UV>,faces:List<List<UV>>):List<List<UV>> {
        val wanted=area(triangle)
        if(wanted<1e-12)return listOf(triangle)
        var remaining=listOf(triangle)
        for(face in faces) {
            remaining=remaining.flatMap { subtract(it,face) }
            if(remaining.sumOf(::area)<=maxOf(1e-8,wanted*1e-5))return emptyList()
        }
        return remaining
    }
    private fun subtract(subject:List<UV>,clip:List<UV>):List<List<UV>> {
        if(clip.size<3)return listOf(subject)
        if(subject.maxOf { it.u }<clip.minOf { it.u } || subject.minOf { it.u }>clip.maxOf { it.u } ||
            subject.maxOf { it.v }<clip.minOf { it.v } || subject.minOf { it.v }>clip.maxOf { it.v })return listOf(subject)
        val sign=if(turn(clip[0],clip[1],clip[2])>=0)1 else -1
        var inside=subject
        val outside=mutableListOf<List<UV>>()
        for(i in clip.indices) {
            if(inside.isEmpty())break
            val a=clip[i];val b=clip[(i+1)%clip.size]
            val piece=half(inside,a,b,sign,false)
            if(area(piece)>1e-12)outside.add(piece)
            inside=half(inside,a,b,sign,true)
        }
        return outside
    }
    private fun half(polygon:List<UV>,a:UV,b:UV,sign:Int,keepInside:Boolean):List<UV> {
        if(polygon.isEmpty())return emptyList()
        val output=mutableListOf<UV>()
        var before=polygon.last();var db=turn(a,b,before)*sign
        fun accepts(value:Double)=if(keepInside)value>=-1e-12 else value<=1e-12
        for(after in polygon) {
            val da=turn(a,b,after)*sign
            if(accepts(db)!=accepts(da)) {
                val t=db/(db-da)
                output.add(UV(before.u+(after.u-before.u)*t,before.v+(after.v-before.v)*t))
            }
            if(accepts(da))output.add(after)
            before=after;db=da
        }
        return output
    }
    private fun turn(a:UV,b:UV,c:UV)=(b.u-a.u)*(c.v-a.v)-(b.v-a.v)*(c.u-a.u)
    private fun area(polygon:List<UV>):Double {
        if(polygon.size<3)return 0.0
        return abs(polygon.indices.sumOf { i -> val a=polygon[i];val b=polygon[(i+1)%polygon.size];a.u*b.v-a.v*b.u })*.5
    }
}
