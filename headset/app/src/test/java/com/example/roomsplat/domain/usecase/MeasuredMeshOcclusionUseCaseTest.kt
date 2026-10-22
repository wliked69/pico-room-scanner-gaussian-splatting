package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import com.example.roomsplat.domain.model.HeadPoseEvidence
import org.junit.Assert.*
import org.junit.Test

class MeasuredMeshOcclusionUseCaseTest {
    private val origin=Point3(0f,0f,0f)
    private fun rectangle(x0:Float,x1:Float,y0:Float,y1:Float,z:Float,excluded:Set<Int> = emptySet())=
        MeasuredMeshOcclusionUseCase.shape(listOf(Point3(x0,y0,z),Point3(x1,y0,z),Point3(x1,y1,z),Point3(x0,y1,z)),listOf(0,1,2,0,2,3),excluded)
    private fun wall()=rectangle(-2f,2f,-2f,2f,-2f)
    private val quad=listOf(Point3(-2f,-2f,-2f),Point3(2f,-2f,-2f),Point3(2f,2f,-2f),Point3(-2f,2f,-2f))
    private val quadIndices=listOf(0,1,2,0,2,3)
    @Test fun unrelatedMeshDoesNotDisableMeasuredPlaneWall() {
        val wall=MeasuredMeshOcclusionUseCase.measuredSource(quad,quadIndices,"WALL")
        val unrelated=MeasuredMeshOcclusionUseCase.measuredSource(quad.map { Point3(it.x+10,it.y,it.z) },quadIndices,"MESH",listOf("WALL","WALL"))
        assertEquals(setOf("far"),MeasuredMeshOcclusionUseCase(listOf(unrelated,wall)).blocked(
            mapOf("wall" to Point3(0f,0f,-2.1f),"far" to Point3(0f,0f,-4f)),origin))
    }
    @Test fun uncertainAndMovingSourcesCannotRetractAnAlreadySeenWall() {
        for(kind in listOf("UNKNOWN","HUMAN","TABLE","CHAIR","CABINET","SCREEN","DOOR","WINDOW","OPENING","VIRTUAL_WALL")) {
            val mesh=MeasuredMeshOcclusionUseCase.measuredSource(quad,quadIndices,"MESH",listOf(kind,kind))
            val plane=MeasuredMeshOcclusionUseCase.measuredSource(quad,quadIndices,kind)
            assertTrue(kind,MeasuredMeshOcclusionUseCase(listOf(mesh,plane)).blocked(mapOf("wall" to Point3(0f,0f,-4f)),origin).isEmpty())
        }
    }
    @Test fun structuralSemanticsAreAppliedPerTriangleWithoutClosingOpenings() {
        val mixed=MeasuredMeshOcclusionUseCase.measuredSource(quad,quadIndices,"MESH",listOf("WALL","OPENING"))
        val query=MeasuredMeshOcclusionUseCase(listOf(mixed))
        assertEquals(1,query.triangleCount)
        assertEquals(setOf("wall"),query.blocked(mapOf("wall" to Point3(2f,-2f,-4f),"opening" to Point3(-2f,2f,-4f)),origin))
    }
    @Test fun absentSemanticsFailOpenWhileStructuralPlanesStillOcclude() {
        assertEquals(0,MeasuredMeshOcclusionUseCase.measuredSource(quad,quadIndices,"MESH").triangleCount)
        for(kind in listOf("WALL","FLOOR","CEILING"))assertEquals(2,MeasuredMeshOcclusionUseCase.measuredSource(quad,quadIndices,kind).triangleCount)
    }
    @Test fun meshWallWithoutAnyPlaneProviderBlocksFarRoomAndKeepsCurrentWall() {
        val query=MeasuredMeshOcclusionUseCase(listOf(wall()))
        assertEquals(setOf("far"),query.blocked(mapOf("front" to Point3(0f,0f,-2f),"far" to Point3(0f,0f,-4f)),origin))
    }
    @Test fun sameWallMeasurementOffsetsDoNotSelfOccludeEvenAtObliqueAngle() {
        val query=MeasuredMeshOcclusionUseCase(listOf(wall()))
        val points=mapOf("same" to Point3(1.5f,0f,-2.13f),"farther" to Point3(0f,0f,-2.2f))
        assertEquals(setOf("farther"),query.blocked(points,origin))
    }
    @Test fun exactMeshDoorwayAndOneCentimetreGapStayOpen() {
        val query=MeasuredMeshOcclusionUseCase(listOf(rectangle(-2f,-.005f,-2f,2f,-2f),rectangle(.005f,2f,-2f,2f,-2f)))
        assertEquals(setOf("solid"),query.blocked(mapOf("gap" to Point3(0f,0f,-4f),"solid" to Point3(.02f,0f,-4f)),origin))
    }
    @Test fun explicitlyExcludedOpeningTrianglesCannotBlock() {
        val query=MeasuredMeshOcclusionUseCase(listOf(rectangle(-2f,2f,-2f,2f,-2f,setOf(0,1))))
        assertEquals(0,query.triangleCount)
        assertTrue(query.blocked(mapOf("p" to Point3(0f,0f,-4f)),origin).isEmpty())
    }
    @Test fun emptyAndReplacedMeshCannotKeepOldBlockingTriangles() {
        val points=mapOf("p" to Point3(0f,0f,-4f))
        assertEquals(setOf("p"),MeasuredMeshOcclusionUseCase(listOf(wall())).blocked(points,origin))
        assertTrue(MeasuredMeshOcclusionUseCase(emptyList()).blocked(points,origin).isEmpty())
        assertTrue(MeasuredMeshOcclusionUseCase(listOf(rectangle(-2f,2f,-2f,2f,-5f))).blocked(points,origin).isEmpty())
    }
    @Test fun twoSidedTrianglesAndAxisParallelRaysAreHandled() {
        val points=listOf(Point3(1f,-2f,-2f),Point3(1f,2f,-2f),Point3(1f,2f,2f),Point3(1f,-2f,2f))
        val shape=MeasuredMeshOcclusionUseCase.shape(points,listOf(2,1,0,3,2,0))
        val query=MeasuredMeshOcclusionUseCase(listOf(shape))
        assertEquals(setOf("right"),query.blocked(mapOf("right" to Point3(2f,0f,0f),"front" to Point3(0f,0f,-4f)),origin))
        assertEquals(setOf("left"),query.blocked(mapOf("left" to Point3(0f,0f,0f)),Point3(2f,0f,0f)))
    }
    @Test fun degenerateFacesDoNotProducePhantomOccluders() {
        val shape=MeasuredMeshOcclusionUseCase.shape(listOf(origin,origin,origin),listOf(0,1,2))
        assertEquals(0,shape.triangleCount)
        assertTrue(MeasuredMeshOcclusionUseCase(listOf(shape)).blocked(mapOf("p" to Point3(0f,0f,-4f)),origin).isEmpty())
    }
    @Test fun meshDrawOcclusionDoesNotEraseRetainedRevealProgress() {
        val target=Point3(0f,0f,-4f);val key=SurfaceCoverageUseCase().key(target)
        val reveal=SurfaceRevealUseCase(SurfaceCoverageUseCase()::key)
        reveal.replaceObserved(listOf(target))
        val pose=HeadPoseEvidence(0,Point3(0f,0f,-3f),Point3(0f,0f,-1f),Point3(1f,0f,0f),Point3(0f,1f,0f),listOf(0f,0f,0f,1f))
        reveal.update(pose,0);assertTrue(reveal.isVisibleKey(key))
        val query=MeasuredMeshOcclusionUseCase(listOf(wall()))
        assertEquals(setOf(key),query.blocked(mapOf(key to target),origin))
        assertTrue(reveal.isVisibleKey(key));assertEquals(1,reveal.historyCount())
        assertTrue(query.blocked(mapOf(key to target),pose.position).isEmpty())
        assertTrue(reveal.isVisibleKey(key))
    }
}
