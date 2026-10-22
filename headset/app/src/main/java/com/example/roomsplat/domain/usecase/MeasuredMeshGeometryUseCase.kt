
package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.sqrt

/** Bounds and area come from actual mesh vertices, including anchors whose local origin is off centre. */
class MeasuredMeshGeometryUseCase {
    data class Bounds(val centre: Point3, val halfExtent: Point3)
    fun bounds(points: List<Point3>): Bounds {
        require(points.isNotEmpty() && points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        val minX=points.minOf { it.x };val maxX=points.maxOf { it.x }
        val minY=points.minOf { it.y };val maxY=points.maxOf { it.y }
        val minZ=points.minOf { it.z };val maxZ=points.maxOf { it.z }
        return Bounds(Point3((minX+maxX)*.5f,(minY+maxY)*.5f,(minZ+maxZ)*.5f),
            Point3(maxOf((maxX-minX)*.5f,.005f),maxOf((maxY-minY)*.5f,.005f),maxOf((maxZ-minZ)*.5f,.005f)))
    }
    fun area(points: List<Point3>, indices: List<Int>): Float {
        require(indices.size%3==0 && indices.all { it in points.indices })
        var total=0.0
        for (i in indices.indices step 3) {
            val a=points[indices[i+1]]-points[indices[i]]
            val b=points[indices[i+2]]-points[indices[i]]
            val x=a.y.toDouble()*b.z-a.z.toDouble()*b.y
            val y=a.z.toDouble()*b.x-a.x.toDouble()*b.z
            val z=a.x.toDouble()*b.y-a.y.toDouble()*b.x
            total += sqrt(x*x+y*y+z*z)*.5
        }
        return total.toFloat()
    }
}
