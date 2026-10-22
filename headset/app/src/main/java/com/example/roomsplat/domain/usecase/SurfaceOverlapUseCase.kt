package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import kotlin.math.abs

/** Display ownership inside measured plane polygons; all eligible junctions retain only uncovered pieces. */
class SurfaceOverlapUseCase<K> {
    private val labelledSurfaces=setOf("WALL","FLOOR","CEILING","TABLE")
    private val union=ProjectedSurfaceUnionUseCase()
    data class Surface<K>(val id:K,val points:List<Point3>,val indices:List<Int>,
        val plane:RegularPlaneMeshUseCase.Region?=null,val kind:String="UNKNOWN",val triangleKinds:List<String> = emptyList())
    data class Display(val suppressed:BooleanArray,val fragments:Map<Int,List<ClippedSurfaceMeshUseCase.Weight>> = emptyMap())
    fun build(surfaces:List<Surface<K>>,cached:Map<K,BooleanArray> = emptyMap()):Map<K,BooleanArray> =
        buildDisplay(surfaces,cached.mapValues { Display(it.value) }).mapValues { it.value.suppressed }
    fun buildDisplay(surfaces:List<Surface<K>>,cached:Map<K,Display> = emptyMap()):Map<K,Display> {
        // Stable input order keeps earlier planes as the owners of their shared regions.
        val planes=surfaces.filter { it.plane!=null }
        val result=linkedMapOf<K,Display>()
        for(surface in surfaces) {
            require(surface.triangleKinds.isEmpty() || surface.triangleKinds.size==surface.indices.size/3)
            val reused=cached[surface.id]
            if(reused!=null) { result[surface.id]=reused;continue }
            val supports=if(surface.plane==null)planes else planes.takeWhile { it.id!=surface.id }
            val mask=BooleanArray(surface.indices.size/3)
            val fragments=linkedMapOf<Int,List<ClippedSurfaceMeshUseCase.Weight>>()
            for(t in mask.indices) {
                val i=t*3
                val a=surface.points[surface.indices[i]]
                val b=surface.points[surface.indices[i+1]]
                val c=surface.points[surface.indices[i+2]]
                val kind=surface.triangleKinds.getOrNull(t)?:surface.kind
                fun tolerance(owner:Surface<K>):Float = when {
                    // Wall plane geometry is the display authority in its measured polygon. The real device
                    // has mesh/plane wall offsets up to ~11cm; do not draw both representations there.
                    surface.plane==null && kind=="WALL" && owner.kind=="WALL" -> .12f
                    kind==owner.kind && kind in labelledSurfaces -> .04f
                    else -> .018f
                }
                mask[t]=supports.any { owner ->
                    // Two measured representations of the same labelled wall/floor can differ by a few cm.
                    // Unknown/mixed labels retain the strict tolerance; different depths and edges still need full support.
                    owner.plane!!.covers(a,b,c,tolerance(owner))
                }
                if(!mask[t]) {
                    val eligible=supports.filter { it.plane!!.supportsLayer(a,b,c,tolerance(it)) }
                    if(eligible.isNotEmpty()) {
                        val ab=b-a;val ac=c-a
                        val n=Point3(ab.y*ac.z-ab.z*ac.y,ab.z*ac.x-ab.x*ac.z,ab.x*ac.y-ab.y*ac.x)
                        val axis=if(abs(n.x)>=abs(n.y)&&abs(n.x)>=abs(n.z))0 else if(abs(n.y)>=abs(n.z))1 else 2
                        fun projected(p:Point3)=when(axis) {
                            0 -> RegularPlaneMeshUseCase.UV(p.y.toDouble(),p.z.toDouble())
                            1 -> RegularPlaneMeshUseCase.UV(p.x.toDouble(),p.z.toDouble())
                            else -> RegularPlaneMeshUseCase.UV(p.x.toDouble(),p.y.toDouble())
                        }
                        val faces=eligible.flatMap { it.plane!!.coverageFaces(a,b,c,axis) }
                        val triangle=listOf(projected(a),projected(b),projected(c))
                        if(faces.isNotEmpty()) {
                            // Every source pair retains its original normal/depth/semantic gate. Plane-to-plane
                            // and strict mixed-label seams also need partial clipping, not whole-face removal.
                            val pieces=union.uncovered(triangle,faces)
                            mask[t]=pieces.isEmpty()
                            if(!mask[t]) {
                                val remaining=pieces.sumOf { polygon -> abs(polygon.indices.sumOf { i ->
                                    val p=polygon[i];val q=polygon[(i+1)%polygon.size];p.u*q.v-p.v*q.u })*.5 }
                                val wanted=abs((triangle[1].u-triangle[0].u)*(triangle[2].v-triangle[0].v)-
                                    (triangle[1].v-triangle[0].v)*(triangle[2].u-triangle[0].u))*.5
                                if(wanted-remaining>maxOf(1e-8,wanted*1e-5)) {
                                    fragments[t]=pieces.flatMap { polygon -> (1 until polygon.lastIndex).flatMap { j ->
                                        listOf(polygon[0],polygon[j],polygon[j+1]).map { p -> ClippedSurfaceMeshUseCase.weight(p,triangle) }
                                    } }
                            }
                                }
                        }
                    }
                }
            }
            result[surface.id]=Display(mask,fragments)
        }
        return result
    }
}
