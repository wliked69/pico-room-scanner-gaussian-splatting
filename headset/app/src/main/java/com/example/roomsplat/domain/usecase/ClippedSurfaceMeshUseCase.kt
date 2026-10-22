package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.usecase.RegularPlaneMeshUseCase.UV

/** Display-only fragments interpolate the original baked vertices; measurement and evidence keys stay intact. */
class ClippedSurfaceMeshUseCase {
    data class Weight(val a:Double,val b:Double,val c:Double)
    data class Mesh(val positions:List<Point3>,val indices:List<Int>,val sourceTriangles:List<Int>)
    fun build(positions:List<Point3>,indices:List<Int>,fragments:Map<Int,List<Weight>>):Mesh {
        val points=positions.toMutableList();val output=ArrayList<Int>(indices.size);val sources=ArrayList<Int>(indices.size/3)
        for(t in 0 until indices.size/3) {
            val clipped=fragments[t]
            if(clipped==null) {
                output.addAll(indices.subList(t*3,t*3+3));sources.add(t)
            } else {
                require(clipped.size%3==0)
                val a=positions[indices[t*3]];val b=positions[indices[t*3+1]];val c=positions[indices[t*3+2]]
                clipped.forEachIndexed { i,w ->
                    output.add(points.size)
                    points.add(Point3((a.x*w.a+b.x*w.b+c.x*w.c).toFloat(),
                        (a.y*w.a+b.y*w.b+c.y*w.c).toFloat(),(a.z*w.a+b.z*w.b+c.z*w.c).toFloat()))
                    if(i%3==0)sources.add(t)
                }
            }
        }
        return Mesh(points,output,sources)
    }
    companion object {
        fun weight(p:UV,triangle:List<UV>):Weight {
            val a=triangle[0];val b=triangle[1];val c=triangle[2]
            val determinant=(b.u-a.u)*(c.v-a.v)-(b.v-a.v)*(c.u-a.u)
            val wb=((p.u-a.u)*(c.v-a.v)-(p.v-a.v)*(c.u-a.u))/determinant
            val wc=((b.u-a.u)*(p.v-a.v)-(b.v-a.v)*(p.u-a.u))/determinant
            return Weight(1-wb-wc,wb,wc)
        }
    }
}
