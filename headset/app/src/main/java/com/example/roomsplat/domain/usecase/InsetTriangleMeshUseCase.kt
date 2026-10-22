package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3

/** Independent inset faces create visible gutters without changing measured geometry or coverage keys. */
class InsetTriangleMeshUseCase {
    data class Mesh(val positions: List<Point3>,val indices: List<Int>)
    fun build(positions: List<Point3>,indices: List<Int>,scale: Float=.82f): Mesh {
        require(scale.isFinite() && scale>0f && scale<=1f)
        require(indices.size%3==0 && indices.all { it in positions.indices })
        val result=ArrayList<Point3>(indices.size)
        val triangles=ArrayList<Int>(indices.size)
        for(i in indices.indices step 3) {
            val a=positions[indices[i]];val b=positions[indices[i+1]];val c=positions[indices[i+2]]
            val centre=Point3((a.x+b.x+c.x)/3f,(a.y+b.y+c.y)/3f,(a.z+b.z+c.z)/3f)
            for(point in listOf(a,b,c)) {
                result.add(Point3(centre.x+(point.x-centre.x)*scale,centre.y+(point.y-centre.y)*scale,centre.z+(point.z-centre.z)*scale))
                triangles.add(triangles.size)
            }
        }
        return Mesh(result,triangles)
    }
}
