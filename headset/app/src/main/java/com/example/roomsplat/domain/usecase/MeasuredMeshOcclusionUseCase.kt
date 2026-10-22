package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.abs
import kotlin.math.sqrt

/** Read-only ray index of raw measured topology, built/queryable off Main. No SDK objects.
 * Small target-plane offsets are ignored to keep noisy measurements of the current wall visible.
 */
class MeasuredMeshOcclusionUseCase(shapes:List<Shape>) {
    internal data class Bounds(val x0:Float,val y0:Float,val z0:Float,val x1:Float,val y1:Float,val z1:Float)
    internal class Ray(val origin:Point3,val target:Point3) {
        val dx=target.x-origin.x;val dy=target.y-origin.y;val dz=target.z-origin.z
        val ix=1f/dx;val iy=1f/dy;val iz=1f/dz
    }
    internal class Triangle(a:Point3,b:Point3,c:Point3) {
        val ax=a.x;val ay=a.y;val az=a.z
        val ex=b.x-a.x;val ey=b.y-a.y;val ez=b.z-a.z
        val fx=c.x-a.x;val fy=c.y-a.y;val fz=c.z-a.z
        val nx=ey*fz-ez*fy;val ny=ez*fx-ex*fz;val nz=ex*fy-ey*fx
        val normalLength=sqrt(nx*nx+ny*ny+nz*nz)
        val bounds=Bounds(minOf(a.x,b.x,c.x),minOf(a.y,b.y,c.y),minOf(a.z,b.z,c.z),
            maxOf(a.x,b.x,c.x),maxOf(a.y,b.y,c.y),maxOf(a.z,b.z,c.z))
        fun hit(r:Ray):Boolean {
            val end=abs(nx*(r.target.x-ax)+ny*(r.target.y-ay)+nz*(r.target.z-az))
            if(end<=TARGET_PLANE_BIAS_M*normalLength)return false
            val sx=r.origin.x-ax;val sy=r.origin.y-ay;val sz=r.origin.z-az
            if(abs(nx*sx+ny*sy+nz*sz)<=.01f*normalLength)return false
            val px=r.dy*fz-r.dz*fy;val py=r.dz*fx-r.dx*fz;val pz=r.dx*fy-r.dy*fx
            val det=ex*px+ey*py+ez*pz
            if(abs(det)<1e-9f)return false
            val inv=1f/det
            val u=(sx*px+sy*py+sz*pz)*inv
            if(u<0f || u>1f)return false
            val qx=sy*ez-sz*ey;val qy=sz*ex-sx*ez;val qz=sx*ey-sy*ex
            val v=(r.dx*qx+r.dy*qy+r.dz*qz)*inv
            if(v<0f || u+v>1f)return false
            val t=(fx*qx+fy*qy+fz*qz)*inv
            return t>0f && t<1f
        }
    }
    internal class Node(val bounds:Bounds,val triangles:List<Triangle> = emptyList(),val left:Node?=null,val right:Node?=null) {
        private fun intersects(r:Ray):Boolean {
            var near=0f;var far=1f
            if(r.ix.isInfinite()) { if(r.origin.x<bounds.x0 || r.origin.x>bounds.x1)return false }
            else { val a=(bounds.x0-r.origin.x)*r.ix;val b=(bounds.x1-r.origin.x)*r.ix
                near=maxOf(near,minOf(a,b));far=minOf(far,maxOf(a,b));if(near>far)return false }
            if(r.iy.isInfinite()) { if(r.origin.y<bounds.y0 || r.origin.y>bounds.y1)return false }
            else { val a=(bounds.y0-r.origin.y)*r.iy;val b=(bounds.y1-r.origin.y)*r.iy
                near=maxOf(near,minOf(a,b));far=minOf(far,maxOf(a,b));if(near>far)return false }
            if(r.iz.isInfinite()) { if(r.origin.z<bounds.z0 || r.origin.z>bounds.z1)return false }
            else { val a=(bounds.z0-r.origin.z)*r.iz;val b=(bounds.z1-r.origin.z)*r.iz
                near=maxOf(near,minOf(a,b));far=minOf(far,maxOf(a,b));if(near>far)return false }
            return true
        }
        fun hit(r:Ray):Boolean {
            if(!intersects(r))return false
            return if(left!=null)left.hit(r) || checkNotNull(right).hit(r) else triangles.any { it.hit(r) }
        }
    }
    class Shape internal constructor(internal val triangles:List<Triangle>) {
        internal val root:Node? by lazy { build(triangles) }
        val triangleCount:Int get()=triangles.size
    }
    val triangleCount=shapes.sumOf { it.triangleCount }
    val backend:String get()=if(NativeMeshRayQuery.available)"native-bvh" else "kotlin-reference"
    private val root by lazy { join(shapes.mapNotNull { it.root }) }
    // Pack anchor + two edge vectors once per immutable geometry revision, on Default.
    private val packed=FloatArray(triangleCount*9).also { out ->
        var i=0
        shapes.forEach { shape -> shape.triangles.forEach { t ->
            out[i++]=t.ax;out[i++]=t.ay;out[i++]=t.az
            out[i++]=t.ex;out[i++]=t.ey;out[i++]=t.ez
            out[i++]=t.fx;out[i++]=t.fy;out[i++]=t.fz
        } }
    }

    fun blocked(points:Map<String,Point3>,origin:Point3):Set<String> {
        if(triangleCount==0 || points.isEmpty())return emptySet()
        if(!NativeMeshRayQuery.available)return blockedReference(points,origin)
        val keys=ArrayList<String>(points.size);val targets=FloatArray(points.size*3);var i=0
        points.forEach { (key,p) -> keys.add(key);targets[i++]=p.x;targets[i++]=p.y;targets[i++]=p.z }
        val flags=NativeMeshRayQuery.queryNative(packed,origin.x,origin.y,origin.z,targets,TARGET_PLANE_BIAS_M)
        check(flags.size==keys.size)
        return buildSet { flags.forEachIndexed { index,blocked -> if(blocked)add(keys[index]) } }
    }
    internal fun blockedReference(points:Map<String,Point3>,origin:Point3):Set<String> {
        val index=root?:return emptySet()
        return buildSet { points.forEach { (key,p) -> if(index.hit(Ray(origin,p)))add(key) } }
    }
    companion object {
        const val TARGET_PLANE_BIAS_M=.15f
        private val structuralKinds=setOf("WALL","FLOOR","CEILING")

        /** Fail open for people, furniture, openings and unclassified reconstruction noise.
         * Both native source types feed one index; an unrelated Mesh cannot disable Plane walls.
         * This admission affects occlusion only, never measured/displayed geometry or scan evidence.
         */
        fun measuredSource(points:List<Point3>,indices:List<Int>,kind:String,triangleKinds:List<String> = emptyList()):Shape {
            require(indices.size%3==0)
            require(triangleKinds.isEmpty() || triangleKinds.size==indices.size/3)
            val excluded=if(kind=="MESH") (0 until indices.size/3).filter {
                triangleKinds.getOrElse(it) { "UNKNOWN" } !in structuralKinds
            }.toSet() else if(kind !in structuralKinds)(0 until indices.size/3).toSet() else emptySet()
            return shape(points,indices,excluded)
        }
        fun shape(points:List<Point3>,indices:List<Int>,excludedTriangles:Set<Int> = emptySet()):Shape {
            require(indices.size%3==0 && indices.all { it in points.indices })
            val triangles=buildList {
                for(i in indices.indices step 3)if(i/3 !in excludedTriangles) {
                    val t=Triangle(points[indices[i]],points[indices[i+1]],points[indices[i+2]])
                    if(t.normalLength>1e-7f)add(t)
                }
            }
            return Shape(triangles)
        }
        private fun bounds(parts:List<Bounds>)=Bounds(parts.minOf { it.x0 }-1e-5f,parts.minOf { it.y0 }-1e-5f,parts.minOf { it.z0 }-1e-5f,
            parts.maxOf { it.x1 }+1e-5f,parts.maxOf { it.y1 }+1e-5f,parts.maxOf { it.z1 }+1e-5f)
        private fun axis(b:Bounds)=if(b.x1-b.x0>=b.y1-b.y0 && b.x1-b.x0>=b.z1-b.z0)0 else if(b.y1-b.y0>=b.z1-b.z0)1 else 2
        private fun centre(b:Bounds,axis:Int)=when(axis) { 0 -> b.x0+b.x1;1 -> b.y0+b.y1;else -> b.z0+b.z1 }
        private fun build(triangles:List<Triangle>):Node? {
            if(triangles.isEmpty())return null
            val b=bounds(triangles.map { it.bounds })
            if(triangles.size<=8)return Node(b,triangles)
            val axis=axis(b);val sorted=triangles.sortedBy { centre(it.bounds,axis) };val half=sorted.size/2
            return Node(b,left=build(sorted.subList(0,half)),right=build(sorted.subList(half,sorted.size)))
        }
        private fun join(nodes:List<Node>):Node? {
            if(nodes.isEmpty())return null
            if(nodes.size==1)return nodes[0]
            val b=bounds(nodes.map { it.bounds });val axis=axis(b)
            val sorted=nodes.sortedBy { centre(it.bounds,axis) };val half=sorted.size/2
            return Node(b,left=join(sorted.subList(0,half)),right=join(sorted.subList(half,sorted.size)))
        }
    }
}
