
package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.abs
import kotlin.math.floor

/** Hold noisy snapshots while retaining real area growth for a later display update. */
class MeshDisplayStabilityUseCase {
    enum class Decision { ACCEPT, DEFER, IGNORE }
    private data class Voxel(val x: Int, val y: Int, val z: Int)
    private fun key(p: Point3) = Voxel(floor(p.x / .2f).toInt(), floor(p.y / .2f).toInt(), floor(p.z / .2f).toInt())
    fun decide(displayed: List<Point3>, incoming: List<Point3>, previousNanos: Long, nowNanos: Long,
        displayedAreaM2: Float? = null, incomingAreaM2: Float? = null,
        displayedVertices: List<Point3>? = null,incomingVertices: List<Point3>? = null): Decision {
        if (incoming.isEmpty()) return Decision.IGNORE
        if (displayed.isEmpty()) return Decision.ACCEPT
        // Retessellation preserves triangle area; filling a nearby hole increases it.
        val areaAdded = displayedAreaM2 != null && incomingAreaM2 != null &&
            displayedAreaM2.isFinite() && incomingAreaM2.isFinite() &&
            incomingAreaM2 - displayedAreaM2 >= .01f
        // A hold expiry does not turn area noise into stable geometry.
        val centresStable=areaAdded && retainedGeometry(displayed,incoming)
        val verticesStable=areaAdded && (displayedVertices==null && incomingVertices==null ||
            displayedVertices!=null && incomingVertices!=null && retainedGeometry(displayedVertices,incomingVertices))
        if(centresStable && verticesStable)return Decision.ACCEPT
        if (nowNanos-previousNanos < 2_000_000_000L) return Decision.DEFER
        fun midpoint(points: List<Point3>) = Point3(
            (points.minOf { it.x } + points.maxOf { it.x }) * .5f,
            (points.minOf { it.y } + points.maxOf { it.y }) * .5f,
            (points.minOf { it.z } + points.maxOf { it.z }) * .5f)
        val before = midpoint(displayed); val after = midpoint(incoming)
        val relocated = abs(before.x-after.x) > .2f || abs(before.y-after.y) > .2f || abs(before.z-after.z) > .2f
        val meaningful = if (relocated || (areaAdded && verticesStable)) true else {
            val neighbours = mutableSetOf<Voxel>()
            displayed.map(::key).distinct().forEach { cell ->
                for (x in -1..1) for (y in -1..1) for (z in -1..1)
                    neighbours.add(Voxel(cell.x+x, cell.y+y, cell.z+z))
            }
            incoming.map(::key).distinct().count { it !in neighbours } >= 3
        }
        if (!meaningful) return Decision.IGNORE
        return Decision.ACCEPT
    }

    private fun retainedGeometry(displayed: List<Point3>,incoming: List<Point3>): Boolean {
        if(displayed.isEmpty() || incoming.isEmpty())return false
        val epsilon=.002f
        fun stableKey(p: Point3)=Voxel(floor(p.x/epsilon).toInt(),floor(p.y/epsilon).toInt(),floor(p.z/epsilon).toInt())
        val bins=incoming.groupBy(::stableKey)
        for(point in displayed) {
            val cell=stableKey(point)
            var retained=false
            for(x in -1..1) for(y in -1..1) for(z in -1..1) {
                if(retained)continue
                retained=bins[Voxel(cell.x+x,cell.y+y,cell.z+z)].orEmpty().any {
                    val dx=point.x-it.x;val dy=point.y-it.y;val dz=point.z-it.z
                    dx*dx+dy*dy+dz*dz<=epsilon*epsilon
                }
            }
            if(!retained)return false
        }
        return true
    }

    fun shouldReplace(displayed: List<Point3>, incoming: List<Point3>, previousNanos: Long, nowNanos: Long): Boolean =
        decide(displayed,incoming,previousNanos,nowNanos) == Decision.ACCEPT
}
