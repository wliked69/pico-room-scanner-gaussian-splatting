package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.abs

/** Immutable geometry in Stage metres. Direction buckets prune candidates only; native
 * triangle-union intersection still decides visibility, including exact measured openings.
 * Build/query on a worker. No SDK objects or mutable scan evidence.
 */
class MeasuredSurfaceOcclusionUseCase(private val planes:List<RegularPlaneMeshUseCase.Region>) {
    val planeCount:Int get()=planes.size

    private class DirectionIndex(val planes:List<RegularPlaneMeshUseCase.Region>,val origin:Point3) {
        private val resolution=24
        private val words=(planes.size+63)/64
        private val bits=LongArray(6*resolution*resolution*words)

        init {
            planes.forEachIndexed { id,region ->
                if(abs(region.normal.dot(origin)-region.distance)>.01f) {
                    for(face in 0..5) {
                        var minU=1f;var maxU=-1f;var minV=1f;var maxV=-1f
                        region.measuredFaces.forEach { triangle ->
                            var polygon=triangle.map { p -> local(p-origin,face) }
                            for(side in 0..4) {
                                if(polygon.isEmpty())break
                                polygon=clip(polygon,side)
                            }
                            polygon.forEach { p ->
                                val u=p.x/p.z;val v=p.y/p.z
                                minU=minOf(minU,u);maxU=maxOf(maxU,u);minV=minOf(minV,v);maxV=maxOf(maxV,v)
                            }
                        }
                        if(minU>maxU || minV>maxV)continue
                        // Conservative broad-phase padding only; does not fill native polygons.
                        for(u in bin(minU-.00001f)..bin(maxU+.00001f))
                            for(v in bin(minV-.00001f)..bin(maxV+.00001f)) {
                                val offset=((face*resolution+u)*resolution+v)*words+id/64
                                bits[offset]=bits[offset] or (1L shl (id%64))
                            }
                    }
                }
            }
        }

        private fun local(p:Point3,face:Int)=when(face) {
            0 -> Point3(p.y,p.z,p.x)
            1 -> Point3(p.y,p.z,-p.x)
            2 -> Point3(p.x,p.z,p.y)
            3 -> Point3(p.x,p.z,-p.y)
            4 -> Point3(p.x,p.y,p.z)
            else -> Point3(p.x,p.y,-p.z)
        }
        private fun side(p:Point3,side:Int)=when(side) {
            0 -> p.z-1e-6f
            1 -> p.z+p.x
            2 -> p.z-p.x
            3 -> p.z+p.y
            else -> p.z-p.y
        }
        private fun clip(polygon:List<Point3>,plane:Int):List<Point3> {
            val output=ArrayList<Point3>(polygon.size+1)
            var previous=polygon.last();var before=side(previous,plane)
            for(next in polygon) {
                val after=side(next,plane)
                if((before>=0f)!=(after>=0f)) {
                    val t=before/(before-after)
                    output.add(Point3(previous.x+(next.x-previous.x)*t,
                        previous.y+(next.y-previous.y)*t,previous.z+(next.z-previous.z)*t))
                }
                if(after>=0f)output.add(next)
                previous=next;before=after
            }
            return output
        }
        private fun bin(coordinate:Float)=((coordinate+1f)*(.5f*resolution)).toInt().coerceIn(0,resolution-1)

        fun blocked(target:Point3):Boolean {
            val x=target.x-origin.x;val y=target.y-origin.y;val z=target.z-origin.z
            val ax=abs(x);val ay=abs(y);val az=abs(z)
            val face:Int;val u:Float;val v:Float
            if(ax>=ay && ax>=az) {
                if(ax<1e-6f)return false
                face=if(x>=0f)0 else 1;u=y/ax;v=z/ax
            } else if(ay>=az) {
                face=if(y>=0f)2 else 3;u=x/ay;v=z/ay
            } else {
                face=if(z>=0f)4 else 5;u=x/az;v=y/az
            }
            val base=((face*resolution+bin(u))*resolution+bin(v))*words
            for(word in 0 until words) {
                var candidates=bits[base+word]
                while(candidates!=0L) {
                    val bit=java.lang.Long.numberOfTrailingZeros(candidates)
                    if(planes[word*64+bit].obstructsSegment(origin,target))return true
                    candidates=candidates and (candidates-1)
                }
            }
            return false
        }
    }

    fun blocked(points:Map<String,Point3>,origin:Point3):Set<String> {
        if(planes.isEmpty() || points.isEmpty())return emptySet()
        val index=DirectionIndex(planes,origin)
        return buildSet {
            points.forEach { (key,point) -> if(index.blocked(point))add(key) }
        }
    }
}
