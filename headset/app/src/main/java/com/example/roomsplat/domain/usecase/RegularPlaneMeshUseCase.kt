package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Display tessellation of the measured triangle union, never of its enclosing rectangle. */
class RegularPlaneMeshUseCase {
    data class Mesh(val positions: List<Point3>, val indices: List<Int>, val measured: Region,
        val support: Region?, val reusedFrame: Boolean)
    data class UV(val u: Double, val v: Double)
    private data class Face(val points: List<UV>) {
        val minU=points.minOf { it.u }; val maxU=points.maxOf { it.u }
        val minV=points.minOf { it.v }; val maxV=points.maxOf { it.v }
    }

    /** Immutable planar support. Large display grids have an index; small native polygons stay compact. */
    class Region private constructor(val normal: Point3, val distance: Float, val axis: Int,
        private val faces: List<Face>, private val vertices: List<Point3>) {
        // Accessed only for compact native occlusion polygons, not fine display support grids.
        val measuredFaces:List<List<Point3>> by lazy { faces.map { f -> f.points.map(::lift) } }
        private val minU=faces.minOfOrNull { it.minU }?:0.0
        private val maxU=faces.maxOfOrNull { it.maxU }?:0.0
        private val minV=faces.minOfOrNull { it.minV }?:0.0
        private val maxV=faces.maxOfOrNull { it.maxV }?:0.0
        private val binM=.25
        private fun bin(u: Double,v: Double)=Pair(floor(u/binM).toInt(),floor(v/binM).toInt())
        private val bins: Map<Pair<Int,Int>,List<Int>>? = if(faces.size<128)null else buildMap {
            val scratch=mutableMapOf<Pair<Int,Int>,MutableList<Int>>()
            faces.forEachIndexed { i,f ->
                val a=bin(f.minU,f.minV); val b=bin(f.maxU,f.maxV)
                for(x in a.first..b.first)for(y in a.second..b.second)
                    scratch.getOrPut(Pair(x,y)) { mutableListOf() }.add(i)
            }
            scratch.forEach { (key,value) -> put(key,value.toList()) }
        }
        fun project(p: Point3)=when(axis) {
            0 -> UV(p.y.toDouble(),p.z.toDouble())
            1 -> UV(p.x.toDouble(),p.z.toDouble())
            else -> UV(p.x.toDouble(),p.y.toDouble())
        }
        fun lift(p: UV): Point3=when(axis) {
            0 -> Point3((distance-normal.y*p.u-normal.z*p.v).toFloat()/normal.x,p.u.toFloat(),p.v.toFloat())
            1 -> Point3(p.u.toFloat(),(distance-normal.x*p.u-normal.z*p.v).toFloat()/normal.y,p.v.toFloat())
            else -> Point3(p.u.toFloat(),p.v.toFloat(),(distance-normal.x*p.u-normal.y*p.v).toFloat()/normal.z)
        }
        fun compatible(other: Region)=abs(normal.dot(other.normal))>=.999f &&
            other.vertices.all { abs(normal.dot(it)-distance)<=.015f }

        /** Ray against the native triangle union, not its bounds or the inset display tiles.
         * The endpoint bias keeps slightly offset measurements of the same wall from hiding it.
         * It does not expand polygon edges or close measured door/window openings.
         */
        fun obstructsSegment(origin:Point3,target:Point3,endpointBiasM:Float=.04f):Boolean {
            val start=normal.dot(origin)-distance
            val end=normal.dot(target)-distance
            if(abs(start)<=.01f || abs(end)<=endpointBiasM || start*end>=0f)return false
            val t=start/(start-end)
            val p=project(Point3(origin.x+(target.x-origin.x)*t,
                origin.y+(target.y-origin.y)*t,origin.z+(target.z-origin.z)*t))
            if(p.u<minU || p.u>maxU || p.v<minV || p.v>maxV)return false
            fun contains(f:Face):Boolean {
                if(p.u<f.minU || p.u>f.maxU || p.v<f.minV || p.v>f.maxV)return false
                val a=turn(f.points[0],f.points[1],p)
                val b=turn(f.points[1],f.points[2],p)
                val c=turn(f.points[2],f.points[0],p)
                return (a>=-1e-10 && b>=-1e-10 && c>=-1e-10) ||
                    (a<=1e-10 && b<=1e-10 && c<=1e-10)
            }
            val index=bins
            return if(index==null)faces.any(::contains)
                else index[bin(p.u,p.v)].orEmpty().any { contains(faces[it]) }
        }

        fun supportsLayer(a:Point3,b:Point3,c:Point3,toleranceM:Float):Boolean {
            if(abs(normal.dot(a)-distance)>toleranceM || abs(normal.dot(b)-distance)>toleranceM ||
                abs(normal.dot(c)-distance)>toleranceM)return false
            val n=cross(b-a,c-a);val len=sqrt(n.dot(n))
            return len>1e-8f && abs(n.dot(normal))/len>=.985f
        }
        fun coverageFaces(a:Point3,b:Point3,c:Point3,projectionAxis:Int):List<List<UV>> {
            val pa=project(a);val pb=project(b);val pc=project(c)
            val loU=minOf(pa.u,pb.u,pc.u);val hiU=maxOf(pa.u,pb.u,pc.u)
            val loV=minOf(pa.v,pb.v,pc.v);val hiV=maxOf(pa.v,pb.v,pc.v)
            val candidates=bins?.let { index ->
                val lo=bin(loU,loV);val hi=bin(hiU,hiV);val ids=mutableSetOf<Int>()
                for(x in lo.first..hi.first)for(y in lo.second..hi.second)ids.addAll(index[Pair(x,y)].orEmpty())
                ids.map { faces[it] }
            }?:faces
            return candidates.filter { it.maxU>=loU && it.minU<=hiU && it.maxV>=loV && it.minV<=hiV }
                .map { face -> face.points.map { uv ->
                    val p=lift(uv)
                    when(projectionAxis) { 0 -> UV(p.y.toDouble(),p.z.toDouble());1 -> UV(p.x.toDouble(),p.z.toDouble());else -> UV(p.x.toDouble(),p.y.toDouble()) }
                } }
        }

        /** Clip edge cells to measured faces; this keeps native polygon edges and openings exact. */
        fun fragments(a:Point3,b:Point3,c:Point3):List<List<Point3>> {
            val p=listOf(project(a),project(b),project(c))
            val loU=p.minOf { it.u };val hiU=p.maxOf { it.u }
            val loV=p.minOf { it.v };val hiV=p.maxOf { it.v }
            return faces.asSequence().filter { it.maxU>=loU && it.minU<=hiU && it.maxV>=loV && it.minV<=hiV }
                .map { intersect(p,it.points) }.filter { area(it)>1e-10 }
                .map { polygon -> polygon.map(::lift) }.toList()
        }

        /** Exact projected intersection area catches holes smaller than a grid cell or its sample points. */
        fun covers(a: Point3,b: Point3,c: Point3, toleranceM: Float=.018f): Boolean {
            if(abs(normal.dot(a)-distance)>toleranceM || abs(normal.dot(b)-distance)>toleranceM ||
                abs(normal.dot(c)-distance)>toleranceM)return false
            val abx=b.x-a.x;val aby=b.y-a.y;val abz=b.z-a.z
            val acx=c.x-a.x;val acy=c.y-a.y;val acz=c.z-a.z
            val nx=aby*acz-abz*acy;val ny=abz*acx-abx*acz;val nz=abx*acy-aby*acx
            val length=sqrt(nx*nx+ny*ny+nz*nz)
            if(length<1e-8f || abs(nx*normal.x+ny*normal.y+nz*normal.z)/length<.985f)return false
            val pa=project(a);val pb=project(b);val pc=project(c)
            if(minOf(pa.u,pb.u,pc.u)<minU-1e-6 || maxOf(pa.u,pb.u,pc.u)>maxU+1e-6 ||
                minOf(pa.v,pb.v,pc.v)<minV-1e-6 || maxOf(pa.v,pb.v,pc.v)>maxV+1e-6)return false
            val p=listOf(pa,pb,pc); val wanted=area(p)
            if(wanted<1e-12)return false
            val minU=minOf(pa.u,pb.u,pc.u);val maxU=maxOf(pa.u,pb.u,pc.u)
            val minV=minOf(pa.v,pb.v,pc.v);val maxV=maxOf(pa.v,pb.v,pc.v)
            val candidates=bins?.let { index ->
                val lo=bin(minU,minV);val hi=bin(maxU,maxV);val ids=mutableSetOf<Int>()
                for(x in lo.first..hi.first)for(y in lo.second..hi.second)ids.addAll(index[Pair(x,y)].orEmpty())
                ids.asSequence().map { faces[it] }
            } ?: faces.asSequence()
            var supported=0.0
            for(f in candidates) {
                if(f.maxU<minU || f.minU>maxU || f.maxV<minV || f.minV>maxV)continue
                supported+=area(intersect(p,f.points))
                // Clipped double coordinates are uploaded as Float points; tolerate their sub-micron
                // roundoff (0.01 mm²), rather than rejecting the displayed polygon's own edge fragments.
                if(supported+1e-8>=wanted*(1-1e-5))return true
            }
            return false
        }

        companion object {
            fun from(points: List<Point3>,indices: List<Int>,frame: Region?=null): Region? {
                if(indices.isEmpty())return null
                var normal:Point3?=null
                for(i in indices.indices step 3) {
                    val n=cross(points[indices[i+1]]-points[indices[i]],points[indices[i+2]]-points[indices[i]])
                    val length=sqrt(n.dot(n))
                    if(length>1e-7f) { normal=Point3(n.x/length,n.y/length,n.z/length);break }
                }
                val n=normal?:return null
                val d=n.dot(points[indices.first()])
                if(points.any { abs(n.dot(it)-d)>.015f })return null
                val axis=if(abs(n.x)>=abs(n.y) && abs(n.x)>=abs(n.z))0 else if(abs(n.y)>=abs(n.z))1 else 2
                val provisional=Region(n,d,axis,emptyList(),points)
                val basis=frame?.takeIf { it.compatible(provisional) }?:provisional
                val faces=indices.chunked(3).map { Face(it.map { i -> basis.project(points[i]) }) }
                    .filter { area(it.points)>1e-12 }
                return Region(basis.normal,basis.distance,basis.axis,faces,points)
            }
        }
    }

    fun build(points: List<Point3>,indices: List<Int>,reference: Region?=null,
        edgeM: Float=.12f,maxTriangles: Int=6000): Mesh? {
        require(edgeM>0 && maxTriangles>=2 && indices.size%3==0 && indices.all { it in points.indices })
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        val measured=Region.from(points,indices,reference)?:return null
        val uv=points.map(measured::project)
        val dominant=when(measured.axis) { 0 -> abs(measured.normal.x);1 -> abs(measured.normal.y);else -> abs(measured.normal.z) }
        var step=edgeM*dominant/sqrt(2.0)
        fun bounds()=intArrayOf(floor(uv.minOf { it.u }/step).toInt(),ceil(uv.maxOf { it.u }/step).toInt(),
            floor(uv.minOf { it.v }/step).toInt(),ceil(uv.maxOf { it.v }/step).toInt())
        var box=bounds()
        while((box[1].toLong()-box[0])*(box[3].toLong()-box[2])*2>maxTriangles) {
            step*=1.25;box=bounds()
        }
        repeat(10) {
            val vertices=mutableListOf<Point3>();val output=mutableListOf<Int>();val ids=mutableMapOf<Point3,Int>()
            fun point(x:Int,y:Int)=measured.lift(UV(x*step,y*step))
            fun triangle(a:Point3,b:Point3,c:Point3) {
                val n=cross(b-a,c-a)
                if(n.dot(n)<1e-16f)return
                val ordered=if(n.dot(measured.normal)>=0)listOf(a,b,c) else listOf(a,c,b)
                ordered.forEach { p -> output.add(ids.getOrPut(p) { vertices.add(p);vertices.lastIndex }) }
            }
            fun append(a:Point3,b:Point3,c:Point3) {
                if(measured.covers(a,b,c,.001f))triangle(a,b,c)
                else measured.fragments(a,b,c).forEach { polygon ->
                    for(i in 1 until polygon.lastIndex)triangle(polygon[0],polygon[i],polygon[i+1])
                }
            }
            for(x in box[0] until box[1])for(y in box[2] until box[3]) {
                val a=point(x,y);val b=point(x+1,y);val c=point(x+1,y+1);val d=point(x,y+1)
                append(a,b,c);append(a,c,d)
            }
            if(output.size/3<=maxTriangles)
                return Mesh(vertices,output,measured,Region.from(vertices,output,measured),reference?.compatible(measured)==true)
            step*=1.25;box=bounds()
        }
        // As with measured mesh subdivision, a budget cannot discard native topology or close a hole.
        val retained=points.map { measured.lift(measured.project(it)) }
        return Mesh(retained,indices,measured,Region.from(retained,indices,measured),reference?.compatible(measured)==true)
    }

    companion object {
        private fun cross(a:Point3,b:Point3)=Point3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
        private fun turn(a:UV,b:UV,c:UV)=(b.u-a.u)*(c.v-a.v)-(b.v-a.v)*(c.u-a.u)
        private fun area(p:List<UV>):Double {
            if(p.size<3)return 0.0
            var sum=0.0
            for(i in p.indices) { val a=p[i];val b=p[(i+1)%p.size];sum+=a.u*b.v-a.v*b.u }
            return abs(sum)*.5
        }
        private fun intersect(subject:List<UV>,clip:List<UV>):List<UV> {
            var result=subject
            val orientation=if(turn(clip[0],clip[1],clip[2])>=0)1 else -1
            for(i in clip.indices) {
                if(result.isEmpty())break
                val a=clip[i];val b=clip[(i+1)%clip.size];val next=mutableListOf<UV>()
                var before=result.last();var db=turn(a,b,before)*orientation
                for(after in result) {
                    val da=turn(a,b,after)*orientation
                    if((db>=-1e-12)!=(da>=-1e-12)) {
                        val t=db/(db-da)
                        next.add(UV(before.u+(after.u-before.u)*t,before.v+(after.v-before.v)*t))
                    }
                    if(da>=-1e-12)next.add(after)
                    before=after;db=da
                }
                result=next
            }
            return result
        }
    }
}
