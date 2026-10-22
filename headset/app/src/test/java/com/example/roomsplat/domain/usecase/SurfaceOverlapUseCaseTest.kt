package com.example.roomsplat.domain.usecase

import com.example.roomsplat.domain.model.Point3
import org.junit.Assert.*
import org.junit.Test

class SurfaceOverlapUseCaseTest {
    @Test fun labelledSameWallMeasuredTwiceAtSmallOffsetHasOneGrid() {
        val offset=points.map { Point3(it.x,it.y,.03f) }
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support,"WALL"),
            SurfaceOverlapUseCase.Surface("mesh",offset,indices,triangleKinds=listOf("WALL","WALL"))))
        assertTrue(masks["mesh"]!!.all { it })
    }
    @Test fun ambiguousLabelsAndSeparateObjectsKeepStrictDepthBoundary() {
        val offset=points.map { Point3(it.x,it.y,.03f) }
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support,"WALL"),
            SurfaceOverlapUseCase.Surface("unknown",offset,indices),
            SurfaceOverlapUseCase.Surface("furniture",offset,indices,triangleKinds=listOf("TABLE","TABLE"))))
        assertTrue(masks["unknown"]!!.none { it });assertTrue(masks["furniture"]!!.none { it })
    }
    @Test fun sameLabelDoesNotMergeDistinctLayersOrFillPolygonEdges() {
        val offset=points.map { Point3(it.x,it.y,.08f) }
        val crossing=listOf(Point3(.8f,.2f,.03f),Point3(1.2f,.2f,.03f),Point3(.8f,.7f,.03f))
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support,"WALL"),
            SurfaceOverlapUseCase.Surface("depth",offset,indices,RegularPlaneMeshUseCase.Region.from(offset,indices),"WALL"),
            SurfaceOverlapUseCase.Surface("edge",crossing,listOf(0,1,2),triangleKinds=listOf("WALL"))))
        assertTrue(masks["depth"]!!.none { it });assertFalse(masks["edge"]!![0])
    }
    @Test fun measuredWallPlaneOwnsOffsetMeshButKeepsDifferentObjects() {
        val offset=points.map { Point3(it.x,it.y,.09f) }
        val distant=points.map { Point3(it.x,it.y,.16f) }
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support,"WALL"),
            SurfaceOverlapUseCase.Surface("wall",offset,indices,triangleKinds=listOf("WALL","WALL")),
            SurfaceOverlapUseCase.Surface("door",offset,indices,triangleKinds=listOf("DOOR","DOOR")),
            SurfaceOverlapUseCase.Surface("distant",distant,indices,triangleKinds=listOf("WALL","WALL"))))
        assertTrue(masks["wall"]!!.all { it })
        assertTrue(masks["door"]!!.none { it });assertTrue(masks["distant"]!!.none { it })
    }
    @Test fun twoMeasuredWallPatchesJointlyOwnASeamTriangle() {
        val left=points.map { Point3(it.x*.5f,it.y,0f) }
        val right=points.map { Point3(.5f+it.x*.5f,it.y,0f) }
        val mesh=listOf(Point3(.1f,.1f,.06f),Point3(.9f,.1f,.06f),Point3(.1f,.9f,.06f))
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("left",left,indices,RegularPlaneMeshUseCase.Region.from(left,indices),"WALL"),
            SurfaceOverlapUseCase.Surface("right",right,indices,RegularPlaneMeshUseCase.Region.from(right,indices),"WALL"),
            SurfaceOverlapUseCase.Surface("seam",mesh,listOf(0,1,2),triangleKinds=listOf("WALL"))))
        assertTrue(masks["seam"]!![0])
    }
    private val points=listOf(Point3(0f,0f,0f),Point3(1f,0f,0f),Point3(1f,1f,0f),Point3(0f,1f,0f))
    private val indices=listOf(0,1,2,0,2,3)
    private val support=RegularPlaneMeshUseCase.Region.from(points,indices)!!
    @Test fun coplanarDuplicateHasOneOwner() {
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support),
            SurfaceOverlapUseCase.Surface("second-plane",points,indices,support),
            SurfaceOverlapUseCase.Surface("mesh",points,indices)))
        assertTrue(masks["plane"]!!.none { it })
        assertTrue(masks["second-plane"]!!.all { it })
        assertTrue(masks["mesh"]!!.all { it })
    }
    @Test fun depthAndCornersRemainIndependent() {
        val separated=points.map { Point3(it.x,it.y,.08f) }
        val corner=points.map { Point3(it.x,0f,it.y) }
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support),
            SurfaceOverlapUseCase.Surface("depth",separated,indices),
            SurfaceOverlapUseCase.Surface("corner",corner,indices)))
        assertTrue(masks["depth"]!!.none { it })
        assertTrue(masks["corner"]!!.none { it })
    }
    @Test fun boundaryTriangleIsKeptUntilItsFullAreaHasSupport() {
        val partial=listOf(Point3(.8f,.2f,0f),Point3(1.2f,.2f,0f),Point3(.8f,.7f,0f))
        val masks=SurfaceOverlapUseCase<String>().build(listOf(
            SurfaceOverlapUseCase.Surface("plane",points,indices,support),
            SurfaceOverlapUseCase.Surface("edge",partial,listOf(0,1,2))))
        assertFalse(masks["edge"]!![0])
    }
    @Test fun unchangedGeometryReusesItsMaskWithoutCopying() {
        val surfaces=listOf(SurfaceOverlapUseCase.Surface("plane",points,indices,support),
            SurfaceOverlapUseCase.Surface("mesh",points,indices))
        val useCase=SurfaceOverlapUseCase<String>()
        val first=useCase.build(surfaces)
        repeat(100) {
            val next=useCase.build(surfaces,first)
            assertSame(first["mesh"],next["mesh"])
            assertSame(first["plane"],next["plane"])
        }
    }
}
