package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class MeasuredSurfaceOcclusionUseCaseTest {
    private val origin=Point3(0f,0f,0f)
    private fun rectangle(x0:Float,x1:Float,y0:Float,y1:Float,z:Float):RegularPlaneMeshUseCase.Region =
        checkNotNull(RegularPlaneMeshUseCase.Region.from(listOf(Point3(x0,y0,z),Point3(x1,y0,z),
            Point3(x1,y1,z),Point3(x0,y1,z)),listOf(0,1,2,0,2,3)))
    private fun wall()=rectangle(-2f,2f,-2f,2f,-2f)

    @Test fun currentWallBlocksBackRoomWithoutHidingItselfOrNearFurniture() {
        val query=MeasuredSurfaceOcclusionUseCase(listOf(wall()))
        val cells=mapOf("current" to Point3(0f,0f,-2f),"back_room" to Point3(0f,0f,-4f),
            "furniture" to Point3(0f,0f,-1f),"same_wall_noise" to Point3(0f,0f,-2.03f))
        assertEquals(setOf("back_room"),query.blocked(cells,origin))
    }
    @Test fun wallBehindTargetAndSurfacesOnOtherSideOfHeadCannotBlock() {
        val query=MeasuredSurfaceOcclusionUseCase(listOf(rectangle(-2f,2f,-2f,2f,2f),wall()))
        assertTrue(query.blocked(mapOf("front" to Point3(0f,0f,-1f)),origin).isEmpty())
    }
    @Test fun measuredDoorwayRetainsSightIntoNextRoom() {
        val planes=listOf(rectangle(-2f,-.4f,-2f,2f,-2f),rectangle(.4f,2f,-2f,2f,-2f),
            rectangle(-.4f,.4f,1f,2f,-2f))
        val cells=mapOf("through_door" to Point3(0f,0f,-4f),"behind_left_wall" to Point3(-2f,0f,-4f),
            "above_door" to Point3(0f,3f,-4f))
        assertEquals(setOf("behind_left_wall","above_door"),MeasuredSurfaceOcclusionUseCase(planes).blocked(cells,origin))
    }
    @Test fun oneCentimetreOpeningInsideOneNativeRegionIsNotFilledByBounds() {
        val points=listOf(Point3(-1f,-1f,-2f),Point3(-.005f,-1f,-2f),Point3(-.005f,1f,-2f),Point3(-1f,1f,-2f),
            Point3(.005f,-1f,-2f),Point3(1f,-1f,-2f),Point3(1f,1f,-2f),Point3(.005f,1f,-2f))
        val region=checkNotNull(RegularPlaneMeshUseCase.Region.from(points,listOf(0,1,2,0,2,3,4,5,6,4,6,7)))
        assertFalse(region.obstructsSegment(origin,Point3(0f,0f,-4f)))
        assertTrue(region.obstructsSegment(origin,Point3(.02f,0f,-4f)))
    }
    @Test fun outsideMeasuredPolygonAndTriangularCutoutStayOpen() {
        val region=checkNotNull(RegularPlaneMeshUseCase.Region.from(listOf(Point3(-1f,-1f,-2f),
            Point3(1f,-1f,-2f),Point3(-1f,1f,-2f)),listOf(0,1,2)))
        assertFalse(region.obstructsSegment(origin,Point3(1f,1f,-4f)))
        assertFalse(region.obstructsSegment(origin,Point3(5f,0f,-4f)))
        assertTrue(region.obstructsSegment(origin,Point3(-1f,-1f,-4f)))
    }
    @Test fun movingToOtherSideMakesSameBackRoomSurfaceVisible() {
        val query=MeasuredSurfaceOcclusionUseCase(listOf(wall()))
        val cells=mapOf("back_room" to Point3(0f,0f,-4f))
        assertEquals(setOf("back_room"),query.blocked(cells,origin))
        assertTrue(query.blocked(cells,Point3(0f,0f,-3f)).isEmpty())
    }
    @Test fun endpointBiasDoesNotIgnoreAnotherWallFiveCentimetresBeyondFrontWall() {
        assertTrue(wall().obstructsSegment(origin,Point3(0f,0f,-2.05f)))
        assertFalse(wall().obstructsSegment(origin,Point3(0f,0f,-2.035f)))
    }
    @Test fun floorAndSideWallWorkInTheirActualStageAxesAndBothWindings() {
        val floor=checkNotNull(RegularPlaneMeshUseCase.Region.from(listOf(Point3(-2f,-1f,-2f),
            Point3(2f,-1f,-2f),Point3(2f,-1f,2f),Point3(-2f,-1f,2f)),listOf(2,1,0,3,2,0)))
        assertTrue(floor.obstructsSegment(origin,Point3(0f,-2f,0f)))
        val side=checkNotNull(RegularPlaneMeshUseCase.Region.from(listOf(Point3(1f,-2f,-2f),
            Point3(1f,2f,-2f),Point3(1f,2f,2f),Point3(1f,-2f,2f)),listOf(0,1,2,0,2,3)))
        assertTrue(side.obstructsSegment(origin,Point3(2f,0f,0f)))
    }
    @Test fun emptyAndReplacedGeometryCannotRetainOldOccluders() {
        val cells=mapOf("back_room" to Point3(0f,0f,-4f))
        assertTrue(MeasuredSurfaceOcclusionUseCase(emptyList()).blocked(cells,origin).isEmpty())
        assertEquals(setOf("back_room"),MeasuredSurfaceOcclusionUseCase(listOf(wall())).blocked(cells,origin))
        assertTrue(MeasuredSurfaceOcclusionUseCase(listOf(rectangle(-2f,2f,-2f,2f,-5f))).blocked(cells,origin).isEmpty())
    }
    @Test fun indexedResultsMatchExactPolygonQueriesAcrossPositionsIncludingParallelRays() {
        val planes=(0..11).map { i -> rectangle(i*.3f-2f,i*.3f-1.8f,-1f,2f,-1f-i*.2f) }
        val points=buildMap {
            for(x in -15..15)for(y in -5..5)for(z in -6..1)
                put("$x,$y,$z",Point3(x*.2f,y*.3f,z.toFloat()))
        }
        val query=MeasuredSurfaceOcclusionUseCase(planes)
        listOf(origin,Point3(.5f,1f,-2f),Point3(-2f,-1f,-1f)).forEach { eye ->
            val exact=points.filterValues { p -> planes.any { it.obstructsSegment(eye,p) } }.keys
            assertEquals(exact,query.blocked(points,eye))
        }
    }
    @Test fun indexBoundsFollowActualTiltedPlaneAndReusedMeasurementFrame() {
        val original=checkNotNull(RegularPlaneMeshUseCase.Region.from(listOf(Point3(-1f,-1f,-2f),
            Point3(1f,-1f,-1f),Point3(1f,1f,-1f),Point3(-1f,1f,-2f)),listOf(0,1,2,0,2,3)))
        val moved=checkNotNull(RegularPlaneMeshUseCase.Region.from(listOf(Point3(-1f,-1f,-2.01f),
            Point3(1f,-1f,-1.01f),Point3(1f,1f,-1.01f),Point3(-1f,1f,-2.01f)),listOf(0,1,2,0,2,3),original))
        val points=(0..100).associate { i -> "p$i" to Point3((i-50)*.04f,0f,-4f) }
        assertEquals(points.filterValues { moved.obstructsSegment(origin,it) }.keys,
            MeasuredSurfaceOcclusionUseCase(listOf(moved)).blocked(points,origin))
    }
    @Test fun directionalPruningMatchesExactAcrossAllSixDirectionsAndMoreThan64Planes() {
        val planes=(0..69).map { i ->
            val shift=(i%10)*.1f
            val z=if(i<35)-1f-i*.02f else 1f+(i-35)*.02f
            rectangle(-2f+shift,2f+shift,-2f,2f,z)
        }
        val points=buildMap {
            for(x in -5..5)for(y in -5..5)for(z in -5..5)
                put("$x,$y,$z",Point3(x.toFloat(),y.toFloat(),z.toFloat()))
        }
        val query=MeasuredSurfaceOcclusionUseCase(planes)
        listOf(origin,Point3(1f,1f,-1f),Point3(-2f,-2f,1f)).forEach { eye ->
            assertEquals(points.filterValues { p -> planes.any { it.obstructsSegment(eye,p) } }.keys,
                query.blocked(points,eye))
        }
    }
}
